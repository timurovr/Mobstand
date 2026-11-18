#version 150

// The Ender World's Time Stop: a sphere of inverted colour bursting out of the clock on its chest,
// with a bright rim, swallowing the whole screen.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

uniform float CenterX;
uniform float CenterY;
uniform float Radius;
uniform float Aspect;

out vec4 fragColor;

void main() {
    vec4 c = texture(DiffuseSampler, texCoord);
    vec2 d = texCoord - vec2(CenterX, CenterY);
    d.x *= Aspect;
    float dist = length(d);
    float inside = 1.0 - smoothstep(Radius - 0.006, Radius + 0.006, dist);
    float rim = (1.0 - smoothstep(0.0, 0.035, abs(dist - Radius))) * step(0.001, Radius);
    vec3 col = mix(c.rgb, 1.0 - c.rgb, inside);
    col = mix(col, vec3(1.0, 0.95, 0.8), rim * 0.75);
    fragColor = vec4(col, 1.0);
}
