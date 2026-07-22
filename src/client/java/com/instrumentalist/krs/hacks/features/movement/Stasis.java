package com.instrumentalist.krs.hacks.features.movement;

import com.instrumentalist.krs.hacks.Module;
import com.instrumentalist.krs.hacks.ModuleCategory;
import com.instrumentalist.krs.utils.value.BooleanValue;
import com.instrumentalist.krs.events.features.UpdateEvent;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;


public class Stasis extends Module {
    @Setting
    private final BooleanValue stopX = new BooleanValue("Stop X", true);

    @Setting
    private final BooleanValue stopY = new BooleanValue("Stop Y", true);

    @Setting
    private final BooleanValue stopZ = new BooleanValue("Stop Z", true);

    @Setting
    private final BooleanValue blockInputs = new BooleanValue("Block Inputs", true);


    public Stasis() {

        super("Stasis", ModuleCategory.Movement, GLFW.GLFW_KEY_UNKNOWN, false, true);
    }

    @Override
    public void onEnable() {
        // No special action needed on enable; stopping will be applied each update.
    }

    @Override
    public void onDisable() {
        // Nothing to clean up when disabling.
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        var player = mc.player;
        if (player == null) return;

        // Cancel movement keys if the option is enabled
        if (blockInputs.get()) {
            // Block all movement-related key presses each tick
            // Reset all key bindings (used by some modules)
            KeyMapping.setAll();
        }

        // Reset velocity each frame while the module is active
        if (stopX.get() || stopY.get() || stopZ.get()) {
            double x = 0;
            double y = 0;
            double z = 0;
            if (!stopX.get()) x = player.getDeltaMovement().x;
            if (!stopY.get()) y = player.getDeltaMovement().y;
            if (!stopZ.get()) z = player.getDeltaMovement().z;
            player.setDeltaMovement(x, y, z);
        } else {
            // If no axes are stopped, clear velocity entirely
            player.setDeltaMovement(0, 0, 0);
        }
    }
}
