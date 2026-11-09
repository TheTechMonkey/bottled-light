package com.techmonkey.bottledlight.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Accessor("GLOBALS_SNIPPET")
    static RenderPipeline.Snippet bottledlight$getGlobalsSnippet() {
        throw new AssertionError();
    }

    @Invoker("register")
    static RenderPipeline bottledlight$register(RenderPipeline pipeline) {
        throw new AssertionError();
    }
}
