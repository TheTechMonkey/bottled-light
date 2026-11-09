package com.techmonkey.bottledlight;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class BottledLightClient implements ClientModInitializer {
    private static final int RADIUS_XZ = 10;
    private static final int RADIUS_Y = 6;
    private static final int TICK_INTERVAL = 2;
    private static final int SPARKLE_CHANCE = 2;
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().isModLoaded("lumalight")) {
            LumaLightCompat.register();
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            if (!isHoldingBottledLight(mc)) return;

            tickCounter++;
            if (tickCounter % TICK_INTERVAL != 0) return;
            spawnSparkles(mc.level, mc.player.blockPosition());
        });
    }

    private static boolean isHoldingBottledLight(Minecraft mc) {
        ItemStack main = mc.player.getMainHandItem();
        ItemStack off = mc.player.getOffhandItem();
        return main.is(BottledLight.BOTTLED_LIGHT) || off.is(BottledLight.BOTTLED_LIGHT);
    }

    private static void spawnSparkles(Level level, BlockPos center) {
        RandomSource rng = level.getRandom();

        for (int dx = -RADIUS_XZ; dx <= RADIUS_XZ; dx++) {
            for (int dy = -RADIUS_Y; dy <= RADIUS_Y; dy++) {
                for (int dz = -RADIUS_XZ; dz <= RADIUS_XZ; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(BottledLight.BOTTLED_LIGHT_BLOCK)) continue;

                    DustParticleOptions sparkle = new DustParticleOptions(
                            BottledLightColors.rgb(state.getValue(BottledLightBlock.COLOR)), 0.65F);
                    if (rng.nextInt(SPARKLE_CHANCE) != 0) continue;

                    int count = 1 + rng.nextInt(3);
                    for (int i = 0; i < count; i++) {
                        double x = pos.getX() + 0.5 + (rng.nextDouble() - 0.5) * 0.18;
                        double y = pos.getY() + 0.75 + (rng.nextDouble() * 0.2);
                        double z = pos.getZ() + 0.5 + (rng.nextDouble() - 0.5) * 0.18;
                        double vx = (rng.nextDouble() - 0.5) * 0.003;
                        double vy = rng.nextDouble() * 0.006;
                        double vz = (rng.nextDouble() - 0.5) * 0.003;
                        level.addParticle(sparkle, x, y, z, vx, vy, vz);
                    }
                }
            }
        }
    }
}
