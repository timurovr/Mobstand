package com.mobstands.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Key names that don't change with the keyboard language. Minecraft asks the OS for a key's name, so with a
 * Russian layout Z shows as "Я". The binding itself is by physical key and still works; this only fixes labels.
 */
public final class KeyLabels {
    private KeyLabels() {}

    public static String of(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) {
            int v = key.getValue();
            if (v >= GLFW.GLFW_KEY_A && v <= GLFW.GLFW_KEY_Z) return String.valueOf((char) ('A' + (v - GLFW.GLFW_KEY_A)));
            if (v >= GLFW.GLFW_KEY_0 && v <= GLFW.GLFW_KEY_9) return String.valueOf((char) ('0' + (v - GLFW.GLFW_KEY_0)));
        }
        return mapping.getTranslatedKeyMessage().getString();
    }
}
