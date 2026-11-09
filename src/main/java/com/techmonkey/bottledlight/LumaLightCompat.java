package com.techmonkey.bottledlight;

import dev.lumalight.api.ColoredLight;
import dev.lumalight.api.ColoredLightRegistry;

final class LumaLightCompat {
    private LumaLightCompat() {
    }

    static void register() {
        ColoredLightRegistry.register(BottledLight.BOTTLED_LIGHT_BLOCK,
                (state, level, pos, emission) -> {
                    int rgb = BottledLightColors.rgb(state.getValue(BottledLightBlock.COLOR));
                    float radius = 4.0F + (emission * 0.6F);
                    float intensity = state.getValue(BottledLightBlock.COLOR)
                            == net.minecraft.world.item.DyeColor.BLACK ? 0.78F : 1.0F;
                    return new ColoredLight(rgb, radius, intensity);
                });
    }
}
