package com.techmonkey.bottledlight;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

final class BottledLightShaderCompatibility {
    private static boolean initialized;
    private static boolean irisLoaded;
    private static Method getInstance;
    private static Method isShaderPackInUse;

    private BottledLightShaderCompatibility() {
    }

    static boolean isShaderPackActive() {
        initialize();
        if (!irisLoaded) return false;
        if (getInstance == null || isShaderPackInUse == null) return true;

        try {
            return Boolean.TRUE.equals(isShaderPackInUse.invoke(getInstance.invoke(null)));
        } catch (RuntimeException | ReflectiveOperationException ignored) {
            return true;
        }
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
        if (!irisLoaded) return;

        try {
            Class<?> api = Class.forName(
                    "net.irisshaders.iris.api.v0.IrisApi",
                    false,
                    BottledLightShaderCompatibility.class.getClassLoader());
            getInstance = api.getMethod("getInstance");
            isShaderPackInUse = api.getMethod("isShaderPackInUse");
        } catch (NoSuchMethodException | ClassNotFoundException ignored) {
            // Disable the custom post-process when Iris is present but its API is unavailable.
        }
    }
}
