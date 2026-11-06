// Blade 2D UI fragment-only helpers (fwidth is not available in vertex shaders).

// Coverage of a signed distance with exactly one device pixel of smoothing,
// regardless of GUI scale or pose scaling. dist < 0 is inside.
float ui_coverage(float dist) {
    float width = max(fwidth(dist), 0.0001);
    return clamp(0.5 - dist / width, 0.0, 1.0);
}

// Coverage with a minimum softness in local units (for intentionally soft edges).
float ui_coverageSoft(float dist, float softness) {
    float width = max(fwidth(dist), max(softness, 0.0001));
    return clamp(0.5 - dist / width, 0.0, 1.0);
}

// Maps an offset given in element-local units into the space of a companion
// varying (backdrop UV). Inverts the affine local->screen Jacobian, so a rotated
// or scaled pose refracts in the right direction instead of skewing.
// Must be called in uniform control flow: it takes screen derivatives.
vec2 ui_localOffsetToUv(vec2 offset, vec2 localPos, vec2 uv) {
    vec2 localDx = dFdx(localPos);
    vec2 localDy = dFdy(localPos);
    vec2 uvDx = dFdx(uv);
    vec2 uvDy = dFdy(uv);
    float det = localDx.x * localDy.y - localDy.x * localDx.y;
    if (abs(det) < 1e-9) {
        return vec2(0.0);
    }
    float screenDx = (localDy.y * offset.x - localDy.x * offset.y) / det;
    float screenDy = (localDx.x * offset.y - localDx.y * offset.x) / det;
    return uvDx * screenDx + uvDy * screenDy;
}

// msdfgen reference screen-pixel-range: how many screen pixels one distance
// unit spans at the current sampling density.
float ui_screenPxRange(vec2 texCoord, vec2 atlasSize, float pxRange) {
    vec2 unitRange = vec2(pxRange) / atlasSize;
    vec2 screenTexSize = vec2(1.0) / max(fwidth(texCoord), vec2(0.0000001));
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}
