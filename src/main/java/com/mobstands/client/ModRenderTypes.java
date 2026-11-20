package com.mobstands.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * Our additive glows. Same look as vanilla's lightning render type, but it doesn't write depth: a glow is just
 * light, so rain, water and other see-through things behind it still draw (otherwise a faded glow disc leaves a
 * see-through "hole" in the rain).
 */
public final class ModRenderTypes extends RenderType {
    private ModRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling,
                           boolean sort, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
    }

    private static final RenderType GLOW = create("mobstands_glow", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
            1536, false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                    .setWriteMaskState(COLOR_WRITE)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setOutputState(WEATHER_TARGET)
                    .createCompositeState(false));

    public static RenderType glow() {
        return GLOW;
    }

    /** Return to Zero's cosmos: solid colour, seen from inside, hiding everything beyond it. */
    private static final RenderType COSMOS = create("mobstands_cosmos", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
            262144, false, false, CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .createCompositeState(false));

    public static RenderType cosmos() {
        return COSMOS;
    }
}
