package com.yagah.visarena;

import com.yagah.vismod.team.VisRound;
import com.yagah.visarena.map.Spot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Player helpers shared by the lobby and matches. */
public final class Players {
    private Players() {
    }

    /**
     * Moves a player to a spot, possibly in another dimension. Vanilla checks each move a player sends against where
     * they were when their connection last ticked; our teleports come after that (from server tick events), and on a
     * fast connection the client's reply can arrive before the next connection tick, which reads as an 80-block jump
     * ("moved too quickly") and gets them pulled back. Resetting that starting point here prevents it.
     */
    public static void teleport(ServerPlayer player, ServerLevel level, Spot spot) {
        player.teleportTo(level, spot.pos().x, spot.pos().y, spot.pos().z, spot.yaw(), spot.pitch());
        player.connection.resetPosition();
    }

    /** Puts a player back where they stand on the server, keeping their facing (holding them still). */
    public static void holdAt(ServerPlayer player, Vec3 pos) {
        player.connection.teleport(pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
        player.connection.resetPosition();
    }

    /**
     * A fresh start, as on entering the lobby or a match: adventure mode, healed and fed, nothing held, no effects,
     * fire, fall or momentum, and a Vis round reset (abilities ended, energy full).
     */
    public static void refresh(ServerPlayer player) {
        player.setGameMode(GameType.ADVENTURE);
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.clearFire();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(0);
        player.fallDistance = 0;
        player.setDeltaMovement(Vec3.ZERO);
        VisRound.reset(player);
    }
}
