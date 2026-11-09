#version 330
#extension GL_ARB_separate_shader_objects : require

#define MAX_LIGHTS 64

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ColoredLightData {
    mat4 InverseProjection;
    mat4 InverseViewRotation;
    mat4 ProjectionMatrix;
    mat4 ViewRotation;
    vec4 OutputData;
    vec4 EffectData;
    vec4 Reserved;
    vec4 LightPosRadius[MAX_LIGHTS];
    vec4 LightColorIntensity[MAX_LIGHTS];
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 reconstructPosition(vec2 uv, float depth) {
    float clipDepth = Reserved.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 clip = vec4(uv * 2.0 - 1.0, clipDepth, 1.0);
    vec4 view = InverseProjection * clip;
    float safeW = abs(view.w) < 1.0e-6 ? (view.w < 0.0 ? -1.0e-6 : 1.0e-6) : view.w;
    return (InverseViewRotation * vec4(view.xyz / safeW, 0.0)).xyz;
}

vec3 srgbToLinear(vec3 color) {
    return pow(max(color, vec3(0.0)), vec3(2.2));
}

vec3 linearToSrgb(vec3 color) {
    return pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
}

void main() {
    vec3 sceneColor = texture(DiffuseSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    int lightCount = int(OutputData.z + 0.5);
    if (lightCount <= 0 || EffectData.x <= 0.0 || depth <= 0.000001) {
        fragColor = vec4(max(sceneColor, vec3(0.0)), 1.0);
        return;
    }

    vec3 surface = reconstructPosition(texCoord, depth);
    if (length(surface) >= max(EffectData.y * 1.35, 256.0)) {
        fragColor = vec4(max(sceneColor, vec3(0.0)), 1.0);
        return;
    }

    vec3 accumulatedRadiance = vec3(0.0);
    for (int index = 0; index < MAX_LIGHTS; index++) {
        if (index >= lightCount) break;
        vec4 positionRadius = LightPosRadius[index];
        vec4 colorIntensity = LightColorIntensity[index];
        if (colorIntensity.a <= 0.0001) continue;

        float cameraDistance = length(positionRadius.xyz);
        float cameraFade = 1.0 - smoothstep(max(EffectData.y * 0.78, 1.0), max(EffectData.y, 2.0), cameraDistance);
        if (cameraFade <= 0.0001) continue;

        float distanceToLight = length(positionRadius.xyz - surface);
        float radius = max(positionRadius.w * EffectData.w, 0.25);
        float normalizedDistance = distanceToLight / radius;
        if (normalizedDistance >= 1.0) continue;

        float softness = clamp(Reserved.y, 0.05, 1.0);
        float edge = 1.0 - smoothstep(1.0 - softness, 1.0, normalizedDistance);
        float attenuation = edge / (1.0 + 3.2 * normalizedDistance * normalizedDistance);
        vec3 linearLight = srgbToLinear(colorIntensity.rgb);
        float lightLuma = dot(linearLight, vec3(0.2126, 0.7152, 0.0722));
        linearLight = mix(vec3(lightLuma), linearLight, clamp(EffectData.z, 0.0, 1.5));
        accumulatedRadiance += linearLight * colorIntensity.a * attenuation * cameraFade;
    }

    vec3 sceneLinear = srgbToLinear(sceneColor);
    vec3 materialResponse = mix(vec3(0.16), sqrt(max(sceneLinear, vec3(0.0))), 0.72);
    vec3 addedLight = accumulatedRadiance * materialResponse * EffectData.x * 0.82;
    addedLight /= vec3(1.0) + addedLight * 0.42;
    fragColor = vec4(max(linearToSrgb(sceneLinear + addedLight), vec3(0.0)), 1.0);
}
