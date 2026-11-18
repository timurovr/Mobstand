package com.mobstands.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** All controls are normal Minecraft key mappings, so they are rebindable in Options > Controls. */
public final class StandKeys {
    private static final String CATEGORY = "key.categories.mobstands";

    public static final KeyMapping SUMMON = new KeyMapping("key.mobstands.summon", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);
    public static final KeyMapping BASIC = new KeyMapping("key.mobstands.basic", KeyConflictContext.IN_GAME,
            InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, CATEGORY);
    public static final KeyMapping ABILITY1 = new KeyMapping("key.mobstands.ability1", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    public static final KeyMapping ABILITY2 = new KeyMapping("key.mobstands.ability2", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY);
    public static final KeyMapping ULTIMATE = new KeyMapping("key.mobstands.ultimate", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
    public static final KeyMapping ABILITY4 = new KeyMapping("key.mobstands.ability4", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping POSE = new KeyMapping("key.mobstands.pose", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    public static final KeyMapping INFO = new KeyMapping("key.mobstands.info", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);

    private StandKeys() {}
}
