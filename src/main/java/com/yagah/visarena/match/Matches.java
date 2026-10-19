package com.yagah.visarena.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.yagah.vismod.team.VisAllies;
import com.yagah.visarena.VisArena;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Every match running, who is in which, and the events that reach into them (ticks, damage, deaths, leaving). */
@EventBusSubscriber(modid = VisArena.MODID)
public final class Matches {
    private static final List<Match> ACTIVE = new ArrayList<>();
    private static final Map<UUID, Match> BY_PLAYER = new HashMap<>();

    private Matches() {
    }

    public static Optional<Match> of(ServerPlayer player) {
        return Optional.ofNullable(BY_PLAYER.get(player.getUUID()));
    }

    public static boolean inMatch(ServerPlayer player) {
        return BY_PLAYER.containsKey(player.getUUID());
    }

    public static boolean inUse(ArenaMap map) {
        return ACTIVE.stream().anyMatch(match -> match.map() == map);
    }

    public static List<Match> active() {
        return List.copyOf(ACTIVE);
    }

    /** Starts a match of the mode on the map with these players; empty if the mode can't be played yet. */
    public static Optional<Match> start(MinecraftServer server, ArenaMode mode, ArenaMap map, List<ServerPlayer> players) {
        ServerLevel level = server.getLevel(map.dimension());
        if (level == null || mode.rules().isEmpty() || players.isEmpty()) {
            return Optional.empty();
        }
        Match match = new Match(mode, map, level, mode.rules().get(), players);
        ACTIVE.add(match);
        players.forEach(player -> {
            Queues.leave(player.getUUID());
            BY_PLAYER.put(player.getUUID(), match);
        });
        match.begin();
        return Optional.of(match);
    }

    /** Ends every match with no winner. */
    public static int stopAll() {
        List<Match> matches = List.copyOf(ACTIVE);
        matches.forEach(Match::stop);
        return matches.size();
    }

    /** Called by a match as it finishes: it and its players are let go. */
    static void finished(Match match) {
        ACTIVE.remove(match);
        BY_PLAYER.values().removeIf(other -> other == match);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        Queues.tick(event.getServer());
        for (Match match : List.copyOf(ACTIVE)) {
            match.tick();
        }
    }

    /**
     * Fighters can't be hurt before or after the fight or under spawn protection, nor by anyone outside their match.
     * The void and /kill still get through (and count as a death).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim) || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        Match match = BY_PLAYER.get(victim.getUUID());
        if (match == null) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        boolean outsider = attacker != null && VisAllies.owner(attacker) instanceof ServerPlayer owner && BY_PLAYER.get(owner.getUUID()) != match;
        if (match.shielded(victim) || outsider) {
            event.setCanceled(true);
        }
    }

    /** A fighter never dies: the death is cancelled and the match respawns them. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim && BY_PLAYER.get(victim.getUUID()) instanceof Match match) {
            event.setCanceled(true);
            victim.setHealth(victim.getMaxHealth());
            match.died(victim, event.getSource());
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Queues.leave(player.getUUID());
            Match match = BY_PLAYER.remove(player.getUUID());
            if (match != null) {
                match.remove(player);
            }
        }
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        Match.removeLeftoverTeams(event.getServer());
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        stopAll();
        Queues.clear();
    }
}
