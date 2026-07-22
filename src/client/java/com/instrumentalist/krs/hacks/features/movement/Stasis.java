package com.instrumentalist.krs.hacks.features.movement;

import com.instrumentalist.krs.hacks.Module;
import com.instrumentalist.krs.hacks.ModuleCategory;
import com.instrumentalist.krs.utils.value.BooleanValue;
import com.instrumentalist.krs.events.features.UpdateEvent;
import net.minecraft.client.KeyMapping;
import com.instrumentalist.krs.events.features.SendPacketEvent;
import com.instrumentalist.krs.utils.packet.BlinkUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.lwjgl.glfw.GLFW;

public class Stasis extends Module {
    @Setting
    private final BooleanValue stopX = new BooleanValue("Stop X", true);

    @Setting
    private final BooleanValue stopY = new BooleanValue("Stop Y", true);

    @Setting
    private final BooleanValue stopZ = new BooleanValue("Stop Z", true);

    @Setting
    private final BooleanValue cancelPlayerMovement = new BooleanValue("Cancel Player Movement / Inputs", true);

    // Store motion to maintain while frozen and tick counter for periodic refresh
    private int ticks = 0;
    private double storedX, storedY, storedZ;

    public Stasis() {
        super("Stasis", ModuleCategory.Movement, GLFW.GLFW_KEY_UNKNOWN, false, true);
    }

    @Override
    public void onEnable() {
        BlinkUtil.INSTANCE.setLimiter(false);
        ticks = 0;
        var player = mc.player;
        if (player != null) {
            storedX = player.getDeltaMovement().x;
            storedY = player.getDeltaMovement().y;
            storedZ = player.getDeltaMovement().z;
        }
    }

    @Override
    public void onDisable() {
        BlinkUtil.INSTANCE.setLimiter(false);
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        var player = mc.player;
        if (player == null) return;

        // Cancel movement keys if the option is enabled
        if (cancelPlayerMovement.get()) {
            KeyMapping.setAll();
            player.setDeltaMovement(0, 0, 0);
            return;
        }

        // Increment tick counter and refresh stored motion every 15 ticks to sync with server updates
        ticks++;
        if (ticks >= 15) {
            ticks = 0;
            storedX = player.getDeltaMovement().x;
            storedY = player.getDeltaMovement().y;
            storedZ = player.getDeltaMovement().z;
        }

        // Determine movement components based on the stop flags
        double nx = storedX, ny = storedY, nz = storedZ;
        if (!stopX.get()) nx = player.getDeltaMovement().x;
        if (!stopY.get()) ny = player.getDeltaMovement().y;
        if (!stopZ.get()) nz = player.getDeltaMovement().z;

        // Apply the computed motion and keep position steady
        player.setDeltaMovement(nx, ny, nz);
    }

    @Override
    public void onSendPacket(SendPacketEvent event) {
        var player = mc.player;
        if (cancelPlayerMovement.get() && event.packet instanceof ServerboundMovePlayerPacket && player != null) {
            // Zero vertical motion to prevent falling
            var mv = player.getDeltaMovement();
            player.setDeltaMovement(mv.x, 0.0, mv.z);
            BlinkUtil.INSTANCE.setLimiter(true); // stop sending movement packets
            event.cancel();
            return;
        }
        // If cancellation is active, block all move packets
        if (cancelPlayerMovement.get()) {
            if (event.packet instanceof ServerboundMovePlayerPacket) {
                event.cancel();
                return;
            }
        }
        // Otherwise maintain tick-based cancellation logic
        if (ticks != 0) {
            event.cancel();
        }
    }
}
