#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D DepthSampler;
uniform sampler2D SceneSampler;

#moj_import <furryhook:block_outline_common.glsl>
#moj_import <furryhook:atmosphere_common.glsl>

// Closed-form exponential height fog (the same technique as Unreal's
// "Exponential Height Fog"): the optical depth of sigma(y) = sigma0 *
// exp(-(y - center) / H) integrated along a ray has an exact analytic
// solution, so there is no raymarch loop and no noise field to sample -
// just a couple of exp() calls.
const float SKY_DISTANCE = 600.0;
const float GROUND_OFFSET = 8.0;

// Lateral wander of the lightning channel, as a fraction of its screen length.
const float BOLT_JAG = 0.045;

const float TAU = 6.28318530;
const float INV_TAU = 0.15915494;

// ---------------------------------------------------------------- lightning

// A lightning channel is piecewise linear, so the wander interpolates LINEARLY
// between random nodes. Smoothing the interpolation rounds every corner off and
// turns the bolt into a wet noodle.
float boltKink(float t, float seed, float nodes) {
    float x = t * nodes;
    float node = floor(x);
    float f = x - node;
    float a = hash11(node * 1.37 + seed) - 0.5;
    float b = hash11((node + 1.0) * 1.37 + seed) - 0.5;
    return mix(a, b, f) * 2.0;
}

// Three octaves: the big zigzag, the kinks on each leg, and the fine tremble.
float boltWander(float t, float seed) {
    return boltKink(t, seed, 7.0)
            + boltKink(t, seed + 19.3, 19.0) * 0.34
            + boltKink(t, seed + 41.7, 47.0) * 0.11;
}

// Returns (core, glow) coverage for one channel running a -> b. The fragment is
// measured against the *wandering* path: project onto the straight axis to get
// the position along the bolt, then compare the perpendicular distance with the
// wander offset at that position, which is far cheaper than walking a polyline.
vec2 boltChannel(vec2 p, vec2 a, vec2 b, float seed, float jag, float radius, float taper) {
    vec2 axis = b - a;
    float len = length(axis);
    if (len < 1.0e-5) {
        return vec2(0.0);
    }
    vec2 dir = axis / len;
    vec2 normal = vec2(-dir.y, dir.x);
    vec2 rel = p - a;
    float t = dot(rel, dir) / len;
    float clamped = clamp(t, 0.0, 1.0);

    // Pin the cloud end to the strike point, then let the wander open up.
    float offset = boltWander(clamped, seed) * jag * len * smoothstep(0.0, 0.12, clamped);
    float lateral = abs(dot(rel, normal) - offset);
    float overshoot = (t < 0.0 ? -t : max(t - 1.0, 0.0)) * len;
    float dist = sqrt(lateral * lateral + overshoot * overshoot);

    float width = radius * mix(1.0, 0.3, taper * clamped);
    float core = smoothstep(width, width * 0.25, dist);
    float glow = exp(-dist / max(BoltColor.a, 1.0e-4));
    return vec2(core, glow) * (1.0 - smoothstep(0.82, 1.0, clamped) * taper);
}

vec3 lightning(vec2 p) {
    vec2 top = vec2(BoltEnds.x * FogParams.w, BoltEnds.y);
    vec2 ground = vec2(BoltEnds.z * FogParams.w, BoltEnds.w);
    vec2 axis = ground - top;
    float len = length(axis);
    if (len < 1.0e-5) {
        return vec3(0.0);
    }
    vec2 dir = axis / len;
    float radius = BoltParams.y;
    float seed = BoltParams.z;

    // Nothing outside this capsule can contribute, and that is almost the whole
    // screen - the reject keeps a strike from costing anything off-channel.
    float along = clamp(dot(p - top, dir), 0.0, len);
    float reach = BOLT_JAG * len * 2.5 + len * 0.4 + radius * 4.0 + BoltColor.a * 5.0;
    if (length(p - (top + dir * along)) > reach) {
        return vec3(0.0);
    }

    vec2 normal = vec2(-dir.y, dir.x);
    vec2 accumulated = boltChannel(p, top, ground, seed, BOLT_JAG, radius, 0.35);

    int branches = int(BoltParams.w);
    for (int i = 0; i < 4; i++) {
        if (i >= branches) {
            break;
        }
        float index = float(i);
        float split = 0.22 + 0.62 * hash11(seed + index * 7.31 + 3.1);
        float side = hash11(seed + index * 13.77) < 0.5 ? -1.0 : 1.0;
        float reachOut = 0.12 + 0.22 * hash11(seed + index * 3.19 + 8.4);
        // Branches leave from a point on the wandering path, not the straight
        // axis, otherwise they visibly detach from the channel they fork off.
        vec2 start = top
                + dir * (split * len)
                + normal * (boltWander(split, seed) * BOLT_JAG * len);
        vec2 heading = normalize(dir + normal * side * (0.4 + 0.5 * hash11(seed + index * 5.11)));
        accumulated += boltChannel(
                p,
                start,
                start + heading * (reachOut * len),
                seed + index * 31.7 + 11.0,
                BOLT_JAG * 1.4,
                radius * 0.55,
                1.0
        ) * 0.7;
    }

    // The core blows out to white and the halo keeps the tint - that split is
    // what reads as "too bright to have a color" rather than a glowing wire.
    return (BoltColor.rgb * accumulated.y * 1.6 + vec3(accumulated.x)) * BoltParams.x;
}

// --------------------------------------------------------------------- fog

// Three octaves is plenty for density banding and a third of the cost of the
// shared six-octave fbm, which matters at one sample per layer per pixel.
float fogNoise(vec3 p) {
    return valueNoise(p) * 0.55 + valueNoise(p * 2.13) * 0.3 + valueNoise(p * 4.31) * 0.15;
}

// Henyey-Greenstein, normalized so an isotropic medium returns 1.0.
float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-4), 1.5);
}

// -------------------------------------------------------------- night haze

// The haze is a mist DECK: densest at one absolute world height and thinning
// both ways, sigma(y) = sigma0 * exp(-|y - deck| / H). That is what separates it
// from the fog above, which is anchored to the camera and therefore follows you
// upward - a deck stays put, so climbing a hill leaves the mist lying in the
// valley below where it belongs.
//
// exp(-|u|) has no single closed form along a ray, but it does on each side of
// the crossing, so the integral is exact after one split - still no raymarch and
// therefore no banding, just two more exp() than the fog already pays.

/** Exact optical depth over a segment on which (y0 + dy*t) keeps one sign. */
float hazeSegment(float y0, float dy, float len, float invH) {
    // Named "side" rather than "sign" so it does not shadow the builtin.
    float side = (y0 + dy * len * 0.5) >= 0.0 ? 1.0 : -1.0;
    float base = exp(clamp(-side * y0 * invH, -30.0, 2.0));
    float k = side * dy * invH;
    if (abs(k) < 1.0e-5) {
        return base * len;
    }
    return base * (1.0 - exp(clamp(-k * len, -30.0, 30.0))) / k;
}

float hazeOpticalDepth(float y0, float dy, float len, float invH) {
    if (abs(dy) < 1.0e-4) {
        return exp(clamp(-abs(y0) * invH, -30.0, 2.0)) * len;
    }
    float crossing = -y0 / dy;
    if (crossing > 0.0 && crossing < len) {
        return hazeSegment(y0, dy, crossing, invH)
                + hazeSegment(0.0, dy, len - crossing, invH);
    }
    return hazeSegment(y0, dy, len, invH);
}

// -------------------------------------------------------------------- rain

// Rain lives on vertical cylinders around the camera: the azimuth gives a
// horizontal coordinate that stays put when the camera turns, and the world
// height gives a vertical one, so streaks parallax like geometry instead of
// smearing across the glass the way a flat screen-space overlay does. Each
// shell also has a real distance, so the depth buffer can cut it off.
float rainLayer(vec3 rayDir,
                float hitHorizontal,
                float shell,
                float cellHeight,
                float cellWidth,
                float seed) {
    float horizontal = length(rayDir.xz);
    // Toward the zenith a vertical cylinder degenerates: the height per unit of
    // view angle runs away, cells crush below a pixel and every one of them
    // lands a drop, which paints a solid disc overhead. Capping the slope and
    // fading out well before the cap keeps the layer inside the range where the
    // parametrization still resolves.
    float zenithFade = smoothstep(0.30, 0.62, horizontal);
    float slope = clamp(rayDir.y / max(horizontal, 0.25), -3.2, 3.2);
    float azimuth = atan(rayDir.z, rayDir.x);
    float height = CameraOrigin.y + slope * shell;

    // A whole number of columns around the circle makes the grid wrap cleanly
    // across the branch cut of atan(), which is otherwise a visible seam.
    float columns = max(floor(TAU * shell / cellWidth + 0.5), 8.0);
    float u = (azimuth * INV_TAU + 0.5) * columns;
    float v = (height + RainParams.y) / cellHeight;

    // Derivatives are undefined inside non-uniform branches, so both are taken
    // before anything returns early. The u one is clamped because fwidth spikes
    // on the seam pixel where atan wraps.
    float pixel = clamp(fwidth(u) * cellWidth, 1.0e-5, cellWidth * 0.5);
    // Once a cell is thinner than a pixel the layer can only alias, so fade it
    // out instead - the same reason a mip chain exists.
    float aliasFade = clamp(1.6 - fwidth(v), 0.0, 1.0);

    if (zenithFade <= 0.0 || aliasFade <= 0.0 || hitHorizontal < shell) {
        return 0.0;
    }

    vec2 id = floor(vec2(u, v));
    vec2 f = vec2(u, v) - id;
    vec2 key = vec2(mod(id.x, columns), id.y) + seed;

    if (hash21(key) > RainParams.x) {
        return 0.0;
    }

    bool snow = RainParams.z > 0.5;
    float offsetX = 0.15 + 0.7 * hash21(key + 7.7);
    float offsetY = 0.02 + 0.25 * hash21(key + 3.3);
    // Local position inside the cell in blocks, so a flake is round and a
    // streak keeps its real thickness no matter how the cell is shaped.
    vec2 local = vec2((f.x - offsetX) * cellWidth, (f.y - offsetY) * cellHeight);

    float visibility = zenithFade * aliasFade;

    if (snow) {
        float radius = max(0.075, pixel * 0.6);
        return smoothstep(radius + pixel, max(radius - pixel, 0.0), length(local))
                * min(1.0, 0.075 / radius)
                * visibility;
    }

    float streakLength = (0.35 + 0.35 * hash21(key + 13.1)) * cellHeight;
    float along = local.y / streakLength;
    // The lean is the ratio of sideways drift to fall speed, which for rain is
    // small - a drop is heavy and falls nearly straight. Only the tangential
    // half of the wind shows as a lean, so rain slants when you look across the
    // wind and stands upright when you look into it.
    float tilt = (-WindParams.x * sin(azimuth) + WindParams.y * cos(azimuth))
            * WindParams.z * RainParams.w;
    float lateral = abs(local.x - tilt * local.y);

    // Distant streaks fall under a pixel wide; widening them to a pixel and
    // dimming by the same factor keeps them from strobing as the grid scrolls.
    float halfWidth = max(0.015, pixel * 0.6);
    float body = smoothstep(halfWidth + pixel, max(halfWidth - pixel, 0.0), lateral)
            * min(1.0, 0.015 / halfWidth);
    // A falling drop is motion blurred: a bright head at the leading (lower) end
    // with the trail smearing up behind it, not a symmetric dash.
    float head = smoothstep(-0.08, 0.06, along);
    float tail = 1.0 - smoothstep(0.15, 1.0, along);
    float brightness = 0.55 + 0.45 * hash21(key + 21.7);
    return body * head * tail * brightness * visibility;
}

float rain(vec3 rayDir, float hitHorizontal) {
    // Cell heights divide the CPU-side scroll wrap exactly, so the pattern never
    // jumps when the accumulated offset rolls over.
    float total = rainLayer(rayDir, hitHorizontal, 3.0, 1.6, 0.8, 0.0) * 0.85
            + rainLayer(rayDir, hitHorizontal, 9.0, 3.2, 1.4, 17.3) * 0.7
            + rainLayer(rayDir, hitHorizontal, 26.0, 6.4, 2.6, 41.1) * 0.55;
    return min(total, 1.0);
}

// ----------------------------------------------------------------- ripples

// One impact per grid cell, each running on its own phase so they do not pulse
// in unison. The ring expands and fades over its life; two grids at different
// scales and rates are layered so the result does not read as a lattice.
float rippleGrid(vec2 ground, float size, float rate, float seed, float pixel) {
    vec2 p = ground / size;
    vec2 id = floor(p);
    vec2 key = id + seed;
    if (hash21(key + 3.9) > RippleParams.z) {
        return 0.0;
    }

    vec2 center = vec2(0.2 + 0.6 * hash21(key), 0.2 + 0.6 * hash21(key + 5.1));
    float life = fract(RippleParams.y * rate + hash21(key + 11.7));
    float radius = life * size * 0.45;
    float dist = length((p - id - center) * size);

    // The rim thickens to a pixel when the ring is smaller than one, which is
    // what stops distant ripples from flickering in and out.
    float thickness = max(0.035, pixel);
    float rim = smoothstep(thickness, 0.0, abs(dist - radius));
    // Fades as it spreads, and starts from nothing so it does not pop in.
    return rim * (1.0 - life) * (1.0 - life) * smoothstep(0.0, 0.12, life);
}

// -------------------------------------------------------------------- main

void main() {
    float depth = texture(DepthSampler, uv).r;
    bool sky = depth <= 0.000001;

    vec3 relativeEnd = atmosphereReconstruct(uv, sky ? 1.0 : depth);
    float rawLength = length(relativeEnd);
    vec3 rayDir = rawLength > 1.0e-4 ? relativeEnd / rawLength : vec3(0.0, 0.0, -1.0);
    float rayLength = sky ? SKY_DISTANCE : min(rawLength, SKY_DISTANCE);

    // Surface the view ray lands on, reconstructed from depth alone. Taken here
    // in uniform flow because derivatives are undefined inside a branch, and the
    // sky-only pixels it computes garbage for simply never use it.
    vec3 hitPos = CameraOrigin.xyz + relativeEnd;
    vec3 hitDdx = dFdx(hitPos);
    vec3 hitDdy = dFdy(hitPos);
    vec3 hitCross = cross(hitDdx, hitDdy);
    float hitCrossLength = length(hitCross);
    // Facing the camera rather than trusting the cross product's winding, so
    // floors get ripples and ceilings do not regardless of screen orientation.
    vec3 hitNormal = hitCrossLength > 1.0e-9 ? hitCross / hitCrossLength : vec3(0.0, 1.0, 0.0);
    if (dot(hitNormal, rayDir) > 0.0) {
        hitNormal = -hitNormal;
    }
    // Clamped because the derivative explodes across a silhouette edge, where an
    // unbounded footprint would smear a ring into a blob.
    float surfacePixel = clamp(max(length(hitDdx), length(hitDdy)), 1.0e-4, 0.35);

    float invH = FogParams.x;
    float density = FogColorDensity.a * exp(-GROUND_OFFSET * invH);

    float turbulence = FogExtra.x;
    if (turbulence > 0.0) {
        // Density is modulated in WORLD space, so the mist banks sit still in the
        // world and drift with the wind rather than riding along with the camera.
        // The integral below is unchanged - it just runs on a density that now
        // varies from view ray to view ray.
        vec3 drift = vec3(WindParams.x, 0.0, WindParams.y) * FogExtra.z * 0.35;
        vec3 nearPoint = CameraOrigin.xyz + rayDir * min(rayLength, 40.0) - drift;
        vec3 farPoint = CameraOrigin.xyz + rayDir * min(rayLength, 180.0) - drift;
        float banding = fogNoise(nearPoint * 0.018) * 0.55 + fogNoise(farPoint * 0.007) * 0.45;
        density *= mix(1.0, 0.2 + 1.6 * banding, turbulence);
    }

    float dy = rayDir.y;
    float opticalDepth;
    if (abs(dy) < 1.0e-3) {
        opticalDepth = density * rayLength;
    } else {
        float exponent = clamp(-dy * rayLength * invH, -30.0, 30.0);
        opticalDepth = density * (1.0 - exp(exponent)) / (dy * invH);
    }
    opticalDepth = clamp(opticalDepth, 0.0, 30.0);

    float transmittance = exp(-opticalDepth);
    vec3 scene = texture(SceneSampler, uv).rgb;
    float flash = FlashColor.a;

    // Fog is the volume a strike actually lights up, so it takes the flash
    // hardest; the scene itself is only relit in proportion to what it already
    // reflects, which keeps a flash from flattening the frame into grey.
    vec3 scatter = FogColorDensity.rgb * FogParams.y * (1.0 - transmittance) * (1.0 + flash * 2.5);

    float sunScatter = FogExtra.y * SunDirection.w;
    if (sunScatter > 0.0) {
        // Forward scattering: haze looking toward the sun glows, haze with the
        // sun behind you stays flat. This is what sells fog as a lit volume.
        scatter *= 1.0 + henyeyGreenstein(dot(rayDir, SunDirection.xyz), 0.66) * 0.1 * sunScatter;
    }

    vec3 color = scene * transmittance + scatter;
    if (flash > 0.0) {
        color += scene * FlashColor.rgb * flash * 0.65;
        color += FlashColor.rgb * flash * 0.05;
    }

    // The mist deck sits on top of the fog and under the weather: a raindrop three
    // blocks away is in front of the bank behind it, and a lightning channel is far
    // too bright to be veiled by ground mist.
    if (HazeColor.a > 0.0) {
        float hazeInvH = HazeParams.x;
        float deckOffset = HazeParams.y;
        float hazeDepth = HazeColor.a * hazeOpticalDepth(deckOffset, dy, rayLength, hazeInvH);

        float banks = HazeParams.z;
        if (banks > 0.0) {
            // Sampled where the ray passes through the deck rather than at a fixed
            // distance, so a bank is a place in the world you walk into and out of
            // instead of a pattern painted on the camera.
            float deckHit = clamp(abs(dy) > 1.0e-3 ? -deckOffset / dy : 0.0, 0.0, min(rayLength, 320.0));
            vec3 deckPoint = CameraOrigin.xyz + rayDir * deckHit
                    - vec3(WindParams.x, 0.0, WindParams.y) * FogExtra.z * 0.18;
            // Stretched flat on purpose: mist pools in wide sheets, not in blobs.
            float field = fogNoise(deckPoint * vec3(0.013, 0.055, 0.013));
            hazeDepth *= mix(1.0, 0.15 + 1.75 * field, banks);
        }

        float hazeTransmittance = exp(-clamp(hazeDepth, 0.0, 30.0));
        vec3 hazeScatter = HazeColor.rgb * (1.0 - hazeTransmittance) * (1.0 + flash * 2.5);
        float moonScatter = HazeParams.w;
        if (moonScatter > 0.0) {
            // Sharper forward lobe than the fog uses: the halo around a low moon
            // seen through ground mist is a tight one, not a general glow.
            hazeScatter *= 1.0 + henyeyGreenstein(dot(rayDir, SunDirection.xyz), 0.72) * 0.14 * moonScatter;
        }
        color = color * hazeTransmittance + hazeScatter;
    }

    // Impacts go under the falling rain: a streak in the air is in front of the
    // ground it is about to hit.
    if (RippleParams.x > 0.0 && !sky) {
        float upFacing = smoothstep(0.6, 0.86, hitNormal.y);
        // Past a few dozen blocks a ring is smaller than a pixel and only adds
        // shimmer, so it is dropped rather than filtered.
        float reach = 1.0 - smoothstep(18.0, 42.0, rawLength);
        float strength = RippleParams.x * upFacing * reach;
        if (strength > 0.0) {
            float rings = rippleGrid(hitPos.xz, 1.3, 1.0, 0.0, surfacePixel)
                    + rippleGrid(hitPos.xz, 2.7, 0.6, 31.4, surfacePixel);
            color += RainColor.rgb * min(rings, 1.5) * strength;
        }
    }

    if (RainParams.x > 0.0) {
        float hitHorizontal = sky ? 1.0e6 : rawLength * length(rayDir.xz);
        float coverage = rain(rayDir, hitHorizontal);
        if (coverage > 0.0) {
            color = mix(color, RainColor.rgb * (1.0 + flash * 2.0), coverage * RainColor.a);
        }
    }

    // Occlude the channel against the real distance to the strike rather than
    // against "is this pixel sky": whether the sky writes depth is a backend
    // detail, and testing for it would silently drop every bolt on a backend
    // that does. Comparing distances also puts the channel correctly in front
    // of a hillside that is nearer than the horizon but farther than the strike.
    // It goes on after the rain because a 200 ms blowout should not be veiled.
    float sceneDistance = sky ? 1.0e9 : rawLength;
    if (BoltParams.x > 0.0 && sceneDistance > CameraOrigin.w) {
        color += lightning(vec2(uv.x * FogParams.w, uv.y));
    }

    finalColor = vec4(color, 1.0);
}
