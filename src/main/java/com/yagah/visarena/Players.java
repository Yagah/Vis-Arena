package com.yagah.visarena;

import com.yagah.vismod.team.VisRound;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Player helpers shared by the lobby and matches. */
public final class Players {
    private Players() {
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
        player.getFoodData().setSaturation(5);
        player.fallDistance = 0;
        player.setDeltaMovement(Vec3.ZERO);
        VisRound.reset(player);
    }
}
