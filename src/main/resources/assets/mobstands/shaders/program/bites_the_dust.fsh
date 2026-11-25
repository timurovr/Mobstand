#version 150

// Green Detonator's Bites the Dust, in Minecraft's own blocky pixel style:
//  - the switch clicks: a frame of inverted colour,
//  - the world flips to negative and goes chunky, twists backwards round the middle and tears sideways in blocky bands
//    like a tape being rewound, while a big pixel-art clock winds back faster and faster,
//  - everything is sucked in, then SNAP: a flat white flash, a blocky ring blasting outward, colour pours back.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

uniform vec2 OutSize;
uniform float Aspect;
uniform float Pixel;    // how chunky the world is (0 = sharp, 1 = full blocks)
uniform float Swirl;    // how far the world twists (radians, negative = backwards)
uniform float Scan;     // tape-rewind tearing
uniform float Phase;    // scroll of the tearing bands
uniform float ClockA;   // clock face on/off
uniform float HourA;    // clock hands (radians, 0 = twelve o'clock, clockwise)
uniform float MinA;
uniform float Grey;     // how far the world is flipped to negative
uniform float Echo;     // ghost double image
uniform float Vignette;
uniform float Flash;
uniform float Invert;
uniform float RingR;    // the snap's shockwave
uniform float Ripple;
uniform float Colour;   // JoJo palette swaps (0 = off)
uniform float Palette;  // which palette this frame
uniform float Bands;    // rings of colour rippling out from the middle
uniform float Split;    // blocky colour fringes (in overlay pixels)

out vec4 fragColor;

// A flat, Minecraft-ish palette.
const vec3 LIGHT = vec3(0.62, 0.86, 1.0);
const vec3 DARK  = vec3(0.10, 0.22, 0.36);
const vec3 CREEPER = vec3(0.37, 0.75, 0.30);

// The palettes the picture drifts through: magenta, cyan, gold, creeper green, violet.
vec3 duotone(float idx, float lum) {
    vec3 dark = vec3(0.18, 0.0, 0.32), light = vec3(1.0, 0.45, 0.85);
    if (idx == 1.0) { dark = vec3(0.0, 0.12, 0.30); light = vec3(0.35, 0.95, 0.9); }
    else if (idx == 2.0) { dark = vec3(0.25, 0.08, 0.0); light = vec3(1.0, 0.88, 0.35); }
    else if (idx == 3.0) { dark = vec3(0.02, 0.18, 0.06); light = vec3(0.6, 0.95, 0.4); }
    else if (idx == 4.0) { dark = vec3(0.1, 0.02, 0.26); light = vec3(0.65, 0.6, 1.0); }
    return mix(dark, light, lum);
}

float seg(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

void main() {
    // The overlay grid: one "Minecraft pixel" is 1/120 of the screen's height.
    float big = max(2.0, floor(OutSize.y / 120.0));
    vec2 cellBig = vec2(big) / OutSize;
    vec2 tcBig = (floor(texCoord / cellBig) + 0.5) * cellBig;
    // The world is chunked by a grid that grows with Pixel.
    float px = max(1.0, floor(mix(1.0, big, Pixel)));
    vec2 cell = vec2(px) / OutSize;
    vec2 tc = px > 1.0 ? (floor(texCoord / cell) + 0.5) * cell : texCoord;

    vec2 c = vec2(0.5);
    vec2 p = tc - c;
    p.x *= Aspect;
    float r = length(p);
    vec2 n = r > 0.0001 ? p / r : vec2(0.0);

    // The world twists round the middle, in steps (hardest near the centre).
    float ang = Swirl * exp(-r * 2.2);
    ang = floor(ang * 24.0) / 24.0;
    float cs = cos(ang), sn = sin(ang);
    vec2 q = vec2(p.x * cs - p.y * sn, p.x * sn + p.y * cs);
    if (RingR > 0.0) {
        float x = r - RingR;
        q += n * Ripple * step(abs(x), 0.06) * sign(x + 0.0001);
    }
    vec2 uv = q;
    uv.x /= Aspect;
    uv += c;

    // Tape rewind: blocky bands slide sideways, scrolling up the screen.
    float band = fract(floor(tc.y * 40.0) / 40.0 * 5.0 + Phase);
    float tear = step(band, 0.18);
    uv.x += Scan * tear * (floor(fract(floor(tc.y * 40.0) * 0.37 + Phase) * 4.0) - 1.5) * cellBig.x * 3.0;

    vec3 col = texture(DiffuseSampler, uv).rgb;
    if (Split > 0.0) {
        // Colour fringes: red and blue knocked a few blocks apart.
        vec2 off = vec2(floor(Split) * cellBig.x, 0.0);
        col.r = texture(DiffuseSampler, uv + off).r;
        col.b = texture(DiffuseSampler, uv - off).b;
    }
    if (Echo > 0.0) {
        vec3 ghost = texture(DiffuseSampler, uv + vec2(floor(Echo * 4.0) * cellBig.x, floor(Echo * 2.0) * cellBig.y)).rgb;
        col = mix(col, max(col, ghost), Echo * 0.5);
    }

    // Negative time: the world's colours flip, stepped down like an old palette.
    col = mix(col, 1.0 - col, Grey);
    float levels = mix(256.0, 10.0, Grey * Pixel);
    col = floor(col * levels + 0.5) / levels;
    col = mix(col, col * 0.85 + LIGHT * 0.12, Scan * tear);

    // JoJo-style palette swaps: every few frames the whole picture jumps to a new two-colour palette
    // (magenta, cyan, gold, creeper green, violet...), like the anime's time effects.
    if (Colour > 0.0) {
        float lum = dot(col, vec3(0.3, 0.59, 0.11));
        // Blend smoothly from one palette to the next (no strobing).
        float k = fract(Palette);
        k = k * k * (3.0 - 2.0 * k);
        vec3 duo = mix(duotone(mod(floor(Palette), 5.0), lum), duotone(mod(floor(Palette) + 1.0, 5.0), lum), k);
        col = mix(col, duo, Colour);
    }
    // Rings of colour rippling out from the middle, in blocky steps.
    if (Bands > 0.0) {
        vec2 bp = tcBig - c;
        bp.x *= Aspect;
        float rr = length(bp) * 7.0 - Phase * 1.3;
        float bi = mod(floor(rr), 3.0);
        vec3 tint = bi == 0.0 ? vec3(1.0, 0.3, 0.8) : bi == 1.0 ? vec3(0.3, 1.0, 0.9) : vec3(1.0, 0.9, 0.3);
        float on = step(0.55, fract(rr));
        col = mix(col, col * 0.7 + tint * 0.3, Bands * on * 0.25);
    }

    // The big pixel-art clock winding backwards.
    if (ClockA > 0.5) {
        vec2 cp = tcBig - c;
        cp.x *= Aspect;
        float cr = length(cp);
        float u1 = cellBig.y;                  // one overlay pixel, in clock units
        float R = 0.34;
        float a = atan(cp.x, cp.y);
        float s12 = abs(fract(a / 6.2831853 * 12.0 + 0.5) - 0.5) * 6.2831853 / 12.0 * cr;
        bool rim = abs(cr - R) < u1 * 1.0;
        bool rimEdge = abs(cr - R) < u1 * 2.0;
        bool mark = s12 < u1 * 0.9 && cr > R - u1 * 5.0 && cr < R - u1 * 2.0;
        bool hourH = seg(cp, vec2(0.0), vec2(sin(HourA), cos(HourA)) * R * 0.5) < u1 * 1.2;
        bool minH = seg(cp, vec2(0.0), vec2(sin(MinA), cos(MinA)) * R * 0.78) < u1 * 0.8;
        bool hub = cr < u1 * 1.8;
        if (rimEdge) col = DARK;
        if (rim) col = LIGHT;
        if (mark) col = LIGHT;
        if (hourH || minH) col = vec3(1.0);
        if (hub) col = CREEPER;
    }
    // The snap: a flat blocky ring blasting out.
    if (RingR > 0.0) {
        vec2 cp = tcBig - c;
        cp.x *= Aspect;
        float x = abs(length(cp) - RingR);
        if (x < cellBig.y * 3.0) col = DARK;
        if (x < cellBig.y * 1.5) col = mix(LIGHT, vec3(1.0), 0.5);
    }

    // Vignette in steps.
    vec2 v = tcBig - 0.5;
    v.x *= Aspect;
    float vig = floor(smoothstep(0.35, 1.05, length(v)) * 5.0) / 5.0;
    col *= 1.0 - vig * Vignette;
    col = mix(col, 1.0 - col, step(0.5, Invert));
    col = mix(col, vec3(0.95, 1.0, 1.0), clamp(floor(Flash * 4.0 + 0.5) / 4.0, 0.0, 1.0));
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
