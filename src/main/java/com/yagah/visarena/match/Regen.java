package com.yagah.visarena.match;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.VisArena;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Slow out-of-combat healing in matches ({@link ArenaConfig#REGEN}): once a player has gone a while without taking
 * damage, they heal a little at a time up to a share of their max health. Only damage taken resets the wait, so poking
 * at enemies from cover doesn't stop it; the last part of a health bar is left for healers.
 */
@EventBusSubscriber(modid = VisArena.MODID)
final class Regen {
    /** Server tick each player last took damage. */
    private static final Map<UUID, Integer> LAST_HURT = new HashMap<>();

    private Regen() {
    }

    @SubscribeEvent
    static void onDamaged(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getNewDamage() > 0) {
            LAST_HURT.put(player.getUUID(), player.server.getTickCount());
        }
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ArenaConfig.REGEN.get() || !player.isAlive()) {
            return;
        }
        int now = player.server.getTickCount();
        int interval = ArenaConfig.REGEN_INTERVAL_TICKS.get();
        Integer hurt = LAST_HURT.get(player.getUUID());
        int waited = hurt == null ? Integer.MAX_VALUE : now - hurt;
        int delay = ArenaConfig.REGEN_DELAY_SECONDS.get() * 20;
        // Heal on the delay, then every interval after it (players never hurt just go by the clock).
        if (waited < delay || (hurt == null ? now : waited - delay) % interval != 0) {
            return;
        }
        float cap = (float) (player.getMaxHealth() * ArenaConfig.REGEN_CAP.get());
        if (player.getHealth() >= cap) {
            return;
        }
        Match match = Matches.of(player).orElse(null);
        if (match == null || match.state() != Match.State.PLAYING
                || match.mode() == ArenaMode.DUEL && !ArenaConfig.REGEN_IN_DUEL.get()) {
            return;
        }
        player.setHealth(Math.min(cap, player.getHealth() + ArenaConfig.REGEN_AMOUNT.get().floatValue()));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_HURT.remove(event.getEntity().getUUID());
    }
}
