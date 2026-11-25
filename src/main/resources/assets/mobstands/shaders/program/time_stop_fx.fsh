#version 150

// The Ender World's whole Time Stop on one screen pass:
//  - a glowing orb charging up in the clock on its chest,
//  - a sphere of inverted colour bursting out, its rim bending the world like heat haze with colour fringes,
//  - the sphere sucking back into the clock and leaving the world drained and grey,
//  - the stopped world drained and grey,
//  - colour flooding back out in a ripple when time moves again.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

uniform vec2 OutSize;
uniform float CenterX;
uniform float CenterY;
uniform float Aspect;
uniform float InvR;     // radius of the inverted sphere (0: none)
uniform float RingR;    // radius of the shockwave rim (0: none)
uniform float Ripple;   // how hard the rim bends the world
uniform float Chroma;   // colour split
uniform float Grey;     // how drained the stopped world is
uniform float ResumeR;  // colour floods back inside this radius (0: none)
uniform float Vignette;
uniform float Flash;
uniform float Pulse;    // the clock's tick throb
uniform float Danger;   // last second: red edges
uniform float Pal;      // which anime palette the negative is drifting through
uniform float PalAmt;   // how much of it shows

out vec4 fragColor;

vec3 stoppedLook(vec3 c) {
    // Exactly the old Time Stop look (time_stop.json): a little blue, almost no colour left.
    vec3 o = c * vec3(0.95, 0.97, 1.08) + vec3(0.02, 0.0, 0.04);
    float l = dot(o, vec3(0.3, 0.59, 0.11));
    return (o - l) * 0.12 + l;
}

// The anime's time-stop palettes: magenta, cyan, gold.
vec3 palette(float idx, float lum) {
    vec3 dark = vec3(0.22, 0.0, 0.3), light = vec3(1.0, 0.5, 0.9);
    if (idx == 1.0) { dark = vec3(0.0, 0.1, 0.28); light = vec3(0.45, 0.95, 1.0); }
    else if (idx == 2.0) { dark = vec3(0.26, 0.1, 0.0); light = vec3(1.0, 0.9, 0.45); }
    return mix(dark, light, lum);
}

void main() {
    vec2 ctr = vec2(CenterX, CenterY);
    vec2 d = texCoord - ctr;
    d.x *= Aspect;
    float dist = length(d);
    vec2 dir = dist > 0.0001 ? d / dist : vec2(0.0);
    dir.x /= Aspect;

    // The rim bends the world around it.
    float off = 0.0;
    if (RingR > 0.0) {
        float x = dist - RingR;
        off = Ripple * sin(x * 60.0) * exp(-abs(x) * 16.0);
    }
    if (ResumeR > 0.0) {
        float x = dist - ResumeR;
        off += Ripple * sin(x * 50.0) * exp(-abs(x) * 14.0);
    }
    float ca = Chroma + abs(off) * 0.6;
    vec2 base = texCoord + dir * off;
    vec3 c;
    c.r = texture(DiffuseSampler, base + dir * ca).r;
    c.g = texture(DiffuseSampler, base).g;
    c.b = texture(DiffuseSampler, base - dir * ca).b;

    // Drained and grey, except where colour is flooding back.
    float g = Grey;
    if (ResumeR > 0.0) g *= smoothstep(ResumeR - 0.03, ResumeR + 0.06, dist);
    vec3 col = mix(c, stoppedLook(c), g);

    // The inverted sphere.
    if (InvR > 0.0) {
        float inside = 1.0 - smoothstep(InvR - 0.008, InvR + 0.008, dist);
        vec3 inv = 1.0 - c;
        inv = mix(inv, inv * vec3(1.08, 0.88, 1.28), 0.55); // a violet negative
        // ...drifting through the anime's time-stop palettes (blended smoothly, no strobing).
        if (PalAmt > 0.0) {
            float lum = dot(inv, vec3(0.3, 0.59, 0.11));
            float k = fract(Pal);
            k = k * k * (3.0 - 2.0 * k);
            vec3 tone = mix(palette(mod(floor(Pal), 3.0), lum), palette(mod(floor(Pal) + 1.0, 3.0), lum), k);
            inv = mix(inv, tone, PalAmt);
        }
        col = mix(col, inv, inside);
    }

    // The rim: a hot gold edge with a soft halo.
    if (RingR > 0.0) {
        float x = abs(dist - RingR);
        col += vec3(1.0, 0.86, 0.45) * (exp(-x * 75.0) * 0.95 + exp(-x * 14.0) * 0.3);
        // two fainter echoes trailing behind the edge
        float e1 = abs(dist - RingR * 0.82), e2 = abs(dist - RingR * 0.66);
        col += vec3(0.85, 0.6, 1.0) * exp(-e1 * 110.0) * 0.45 + vec3(0.6, 0.9, 1.0) * exp(-e2 * 130.0) * 0.3;
    }
    if (ResumeR > 0.0) {
        float x = abs(dist - ResumeR);
        col += vec3(1.0, 0.92, 0.7) * exp(-x * 60.0) * 0.6;
    }

    // Vignette, the tick throb, and the last second going red at the edges.
    vec2 v = texCoord - 0.5;
    v.x *= Aspect;
    float edge = smoothstep(0.35, 1.05, length(v));
    col *= 1.0 - edge * (Vignette + Pulse * 0.35);
    col = mix(col, vec3(0.55, 0.03, 0.04), edge * Danger * 0.65);
    col += vec3(0.04, 0.035, 0.05) * Pulse * (1.0 - edge);


    col = mix(col, vec3(1.0, 0.97, 0.9), clamp(Flash, 0.0, 1.0));
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
