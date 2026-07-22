package com.instrumentalist.krs.hacks.features.render;

import com.instrumentalist.krs.events.features.Render3DEvent;
import com.instrumentalist.krs.hacks.Module;
import com.instrumentalist.krs.hacks.ModuleCategory;
import com.instrumentalist.krs.utils.value.IntValue;
import com.instrumentalist.krs.utils.move.MovementUtil;
import org.lwjgl.glfw.GLFW;

public class PredictRender extends Module {
    @Setting
    private final IntValue range = new IntValue("Range", 20, 0, 100);

    public PredictRender() {
        super("Predict Render", ModuleCategory.Render, GLFW.GLFW_KEY_F5, false, true);
    }

    @Override
    public void onEnable() {
        // No special initialization needed.
    }

    @Override
    public void onDisable() {
        // Cleanup if necessary. Currently nothing to clean up.
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        int r = range.get();
        if (r <= 0) return;
        for (int i = 1; i <= r; i++) {
            var pos = MovementUtil.getPredictedPosition(i);
            // TODO: render the predicted position
        }
    }
}
