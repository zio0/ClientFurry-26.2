// Shared by the atmosphere fog pass.

layout(std140) uniform AtmosphereUniforms {
    mat4 InvViewProjection;
    vec4 FogColorDensity; // rgb = fog color, a = density
    vec4 FogParams;       // x = 1 / height falloff, y = ambient strength, z = depth is 0..1, w = aspect ratio
    vec4 FogExtra;        // x = turbulence, y = sun scatter, z = wind drift offset, w = storm intensity
    vec4 SunDirection;    // xyz = direction to sun (or moon at night), w = how much light it gives
    vec4 CameraOrigin;    // xyz = camera world position, wrapped to keep float precision, w = strike distance
    vec4 WindParams;      // xy = wind direction, z = wind strength
    vec4 FlashColor;      // rgb = flash tint, a = flash strength (0 = no strike lit)
    vec4 BoltEnds;        // xy = cloud end in uv, zw = ground end in uv
    vec4 BoltColor;       // rgb = channel tint, a = glow falloff radius
    vec4 BoltParams;      // x = brightness (0 = nothing to draw), y = core radius, z = seed, w = branch count
    vec4 RainColor;       // rgb = streak tint, a = opacity
    vec4 RainParams;      // x = amount (0 = no rain), y = fall offset, z = 1 when snow, w = wind tilt
    vec4 RippleParams;    // x = strength (0 = no ripples), y = phase in seconds, z = density
    vec4 HazeColor;       // rgb = mist tint premultiplied by glow, a = base density (0 = no haze)
    vec4 HazeParams;      // x = 1 / thickness, y = camera height above the deck, z = bank amount, w = moon scatter
};

vec3 atmosphereReconstruct(vec2 coords, float depth) {
    float clipZ = FogParams.z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 ndc = vec4(coords * 2.0 - 1.0, clipZ, 1.0);
    vec4 position = InvViewProjection * ndc;
    return position.xyz / position.w;
}
