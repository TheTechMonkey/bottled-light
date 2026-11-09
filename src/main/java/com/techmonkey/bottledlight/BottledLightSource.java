package com.techmonkey.bottledlight;

import net.minecraft.core.BlockPos;

record BottledLightSource(BlockPos pos, int rgb, float radius, float intensity) {
}
