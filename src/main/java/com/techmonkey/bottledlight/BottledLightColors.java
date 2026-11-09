package com.techmonkey.bottledlight;

import net.minecraft.world.item.DyeColor;

public final class BottledLightColors {
    private BottledLightColors() {
    }

    public static int rgb(DyeColor color) {
        return switch (color) {
            case WHITE -> 0xFFFFFF;
            case ORANGE -> 0xFF8A18;
            case MAGENTA -> 0xE84DFF;
            case LIGHT_BLUE -> 0x67C7FF;
            case YELLOW -> 0xFFE65A;
            case LIME -> 0x8CFF45;
            case PINK -> 0xFF79B7;
            case GRAY -> 0x8A8A98;
            case LIGHT_GRAY -> 0xC6CED7;
            case CYAN -> 0x31E6E6;
            case PURPLE -> 0xB45CFF;
            case BLUE -> 0x3B6DFF;
            case BROWN -> 0xC06B35;
            case GREEN -> 0x36C85F;
            case RED -> 0xFF3B30;
            // RGB cannot represent ultraviolet; electric violet gives the expected black-light look.
            case BLACK -> 0x4B00FF;
        };
    }
}
