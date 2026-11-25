#version 150

// Pigstep's Another World: squeezed between two worlds, a glowing seam splits the screen and tears open like
// doors (blocky, jagged edges), the world on the other side slightly drained and purple. Inside, the tint stays.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

uniform float Aspect;
uniform float Half;     // half-width of the opening (0 = no seam)
uniform float In;       // how "other-world" it looks inside the opening
uniform float Out;      // ...and outside it
uniform float Pinch;    // squeezed in from both sides
uniform float Flash;
uniform float Time;
uniform float Gold;     // 0: amethyst seam (going in), 1: gold seam (coming back)

out vec4 fragColor;

vec3 otherLook(vec3 c) {
    float l = dot(c, vec3(0.3, 0.59, 0.11));
    vec3 g = mix(vec3(l), c, 0.78);
    return g * vec3(1.0, 0.9, 1.12) + vec3(0.035, 0.0, 0.065);
}

float hash(float n) {
    return fract(sin(n * 12.9898) * 43758.5453);
}

void main() {
    vec3 SeamCol = mix(vec3(0.7, 0.45, 1.0), vec3(1.0, 0.8, 0.35), Gold);
    vec2 uv = texCoord;
    float dx = uv.x - 0.5;
    // Squeezed between two worlds.
    uv.x = 0.5 + dx * (1.0 + Pinch * 1.5);
    // The seam's edge: jagged, in blocky rows, flickering.
    float row = floor(texCoord.y * 36.0);
    float jag = (hash(row + floor(Time * 0.5) * 7.0) - 0.5) * 0.035;
    float d = abs(dx) - (Half + jag);
    if (Half > 0.0) uv.x += sign(dx) * 0.014 * exp(-abs(d) * 50.0) * sin(d * 180.0);
    vec3 c = texture(DiffuseSampler, uv).rgb;
    float inside = Half > 0.0 ? step(d, 0.0) : 0.0;
    float t = mix(Out, In, inside);
    c = mix(c, otherLook(c), t);
    vec2 v = texCoord - 0.5;
    v.x *= Aspect;
    c *= 1.0 - smoothstep(0.45, 1.1, length(v)) * 0.35 * t;
    if (Half > 0.0) {
        c += SeamCol * exp(-abs(d) * 90.0) * 1.2 + vec3(1.0) * exp(-abs(d) * 420.0) * 0.8;
    }
    c += SeamCol * exp(-abs(dx) * 80.0) * Pinch * 2.0;
    c = mix(c, vec3(0.95, 0.9, 1.0), clamp(Flash, 0.0, 1.0));
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
