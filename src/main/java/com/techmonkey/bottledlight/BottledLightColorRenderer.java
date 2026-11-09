package com.techmonkey.bottledlight;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.techmonkey.bottledlight.mixin.RenderPipelinesAccessor;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Minimal colored-light post-process derived from LumaLight under the MIT license. */
final class BottledLightColorRenderer {
    private static final int SHADER_MAX_LIGHTS = 64;
    private static final int UBO_SIZE = 2352;
    private static final RenderPipeline PIPELINE = RenderPipelinesAccessor.bottledlight$register(
            RenderPipeline.builder(new Snippet[] {RenderPipelinesAccessor.bottledlight$getGlobalsSnippet()})
                    .withLocation(Identifier.fromNamespaceAndPath("bottledlight", "pipeline/colored_light"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath("bottledlight", "post/colored_light"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform("DiffuseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("ColoredLightData", UniformType.UNIFORM_BUFFER)
                            .build())
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .build());

    private static MappableRingBuffer uniforms;
    private static TextureTarget sceneCopy;
    private static int width = -1;
    private static int height = -1;

    private BottledLightColorRenderer() {
    }

    static void render(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || BottledLightShaderCompatibility.isShaderPackActive()) return;

        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        if (main.getColorTexture() == null || main.getDepthTexture() == null || main.width <= 0 || main.height <= 0) return;

        CameraRenderState camera = context.levelState().cameraRenderState;
        Vec3 cameraPosition = camera.pos;
        List<BottledLightSource> lights = BottledLightManager.updateAndGet(minecraft.level, cameraPosition);
        if (lights.isEmpty()) return;

        ensureTargets(main);
        int count = Math.min(lights.size(), BottledLightManager.MAX_LIGHTS);
        float[] positions = new float[SHADER_MAX_LIGHTS * 4];
        float[] colors = new float[SHADER_MAX_LIGHTS * 4];
        for (int i = 0; i < count; i++) {
            BottledLightSource light = lights.get(i);
            int offset = i * 4;
            positions[offset] = (float) (light.pos().getX() + 0.5 - cameraPosition.x);
            positions[offset + 1] = (float) (light.pos().getY() + 0.5 - cameraPosition.y);
            positions[offset + 2] = (float) (light.pos().getZ() + 0.5 - cameraPosition.z);
            positions[offset + 3] = light.radius();
            colors[offset] = (light.rgb() >> 16 & 0xFF) / 255.0F;
            colors[offset + 1] = (light.rgb() >> 8 & 0xFF) / 255.0F;
            colors[offset + 2] = (light.rgb() & 0xFF) / 255.0F;
            colors[offset + 3] = light.intensity();
        }

        Matrix4f projection = new Matrix4f(camera.projectionMatrix);
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Matrix4f viewRotation = new Matrix4f(camera.viewRotationMatrix);
        Matrix4f inverseViewRotation = new Matrix4f(viewRotation).invert();
        fillUniforms(inverseProjection, inverseViewRotation, projection, viewRotation,
                main.width, main.height, count, positions, colors);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), sceneCopy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        draw(encoder, main.getColorTextureView(), uniforms.currentBuffer(), main);
        uniforms.rotate();
    }

    private static void fillUniforms(
            Matrix4f inverseProjection, Matrix4f inverseViewRotation,
            Matrix4f projection, Matrix4f viewRotation,
            int outputWidth, int outputHeight, int count,
            float[] positions, float[] colors) {
        try (MappedView mapped = uniforms.currentBuffer().map(false, true)) {
            Std140Builder builder = Std140Builder.intoBuffer(mapped.data())
                    .putMat4f(inverseProjection)
                    .putMat4f(inverseViewRotation)
                    .putMat4f(projection)
                    .putMat4f(viewRotation)
                    .putVec4(outputWidth, outputHeight, count, 0.0F)
                    .putVec4(0.72F, BottledLightManager.SCAN_RADIUS, 1.15F, 1.0F)
                    .putVec4(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1.0F : 0.0F,
                            0.55F, 0.0F, 0.0F);
            for (int i = 0; i < SHADER_MAX_LIGHTS; i++) {
                int offset = i * 4;
                builder.putVec4(positions[offset], positions[offset + 1], positions[offset + 2], positions[offset + 3]);
            }
            for (int i = 0; i < SHADER_MAX_LIGHTS; i++) {
                int offset = i * 4;
                builder.putVec4(colors[offset], colors[offset + 1], colors[offset + 2], colors[offset + 3]);
            }
        }
    }

    private static void draw(CommandEncoder encoder, GpuTextureView target, GpuBuffer data, RenderTarget main) {
        try (RenderPass pass = encoder.createRenderPass(() -> "Bottled Light colored light", target, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("ColoredLightData", data);
            pass.setUniform("DiffuseSampler", sceneCopy.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("DepthSampler", main.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static void ensureTargets(RenderTarget main) {
        if (sceneCopy != null && width == main.width && height == main.height) return;
        close();
        width = main.width;
        height = main.height;
        sceneCopy = new TextureTarget("Bottled Light colored scene", width, height, main.getColorTexture().getFormat(), null);
        uniforms = new MappableRingBuffer(() -> "Bottled Light colored UBO", 130, UBO_SIZE);
    }

    static void close() {
        if (uniforms != null) uniforms.close();
        if (sceneCopy != null) sceneCopy.destroyBuffers();
        uniforms = null;
        sceneCopy = null;
        width = -1;
        height = -1;
    }
}
