package com.techmonkey.bottledlight;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public final class BottledLightRendering {
    private static LevelRenderContext pendingContext;

    private BottledLightRendering() {
    }

    static void register() {
        LevelRenderEvents.END_MAIN.register(context -> pendingContext = context);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
            pendingContext = null;
            BottledLightManager.clear();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
            pendingContext = null;
            BottledLightColorRenderer.close();
        });
    }

    public static void afterLevelRender() {
        LevelRenderContext context = pendingContext;
        pendingContext = null;
        if (context != null) BottledLightColorRenderer.render(context);
    }
}
