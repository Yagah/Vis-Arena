package com.yagah.visarena.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.yagah.vismod.team.VisAllies;
import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.Players;
import com.yagah.visarena.VisArena;
import com.yagah.visarena.hud.Sidebar;
import com.yagah.visarena.hud.Titles;
import com.yagah.visarena.lobby.Lobby;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.map.Spot;
import com.yagah.visarena.map.TeamColor;
import com.yagah.visarena.mode.ArenaMode;
import com.yagah.visarena.mode.ModeRules;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * One match on one map: the same flow for every mode. Starting (players frozen at their spawns), playing (the mode's
 * rules keep score until someone wins or time runs out, with sudden death on a tie), ended (the winner shown), then
 * everyone back to the lobby. Deaths never show the death screen: the fighter is healed and respawned on the spot the
 * rules pick, with a moment of spawn protection.
 */
public final class Match {
    public enum State { STARTING, PLAYING, ENDED }

    private static final ResourceLocation FREEZE = ResourceLocation.fromNamespaceAndPath(VisArena.MODID, "freeze");
    /** How far a frozen player may drift before being put back. */
    private static final double FREEZE_SLACK = 0.3;
    private static int nextId;
    /** Every match team is named this, then the match number and the colour. */
    private static final String TEAM_PREFIX = VisArena.MODID + "_";

    private final int id = nextId++;
    private final ArenaMode mode;
    private final ArenaMap map;
    private final ServerLevel level;
    private final ModeRules rules;
    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private final Map<UUID, Spot> frozenAt = new HashMap<>();
    private final Map<TeamColor, PlayerTeam> teams = new EnumMap<>(TeamColor.class);
    private final ServerBossEvent bossBar;
    private final Sidebar sidebar;

    private State state = State.STARTING;
    /** Ticks since the match began (spawn protection counts in these). */
    private int tick;
    /** Ticks since the current state began. */
    private int stateTicks;
    private int timeLeft;
    private boolean suddenDeath;
    /** Set when a kill or a departure may have decided the match, so the rules are asked only then. */
    private boolean scoresChanged = true;
    @Nullable
    private ModeRules.Result result;

    Match(ArenaMode mode, ArenaMap map, ServerLevel level, ModeRules rules, List<ServerPlayer> players) {
        this.mode = mode;
        this.map = map;
        this.level = level;
        this.rules = rules;
        players.forEach(player -> fighters.put(player.getUUID(), new Fighter(player.getUUID(), player.getGameProfile().getName())));
        if (mode.teams()) {
            // Dealt alternately in queue order, so the teams differ by one at most.
            int i = 0;
            for (Fighter fighter : fighters.values()) {
                fighter.setTeam(TeamColor.values()[i++ % TeamColor.values().length]);
            }
        }
        this.bossBar = new ServerBossEvent(Component.literal(mode.displayName()), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        this.sidebar = new Sidebar(VisArena.MODID + "_" + id, Component.literal(mode.displayName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    public ArenaMode mode() {
        return mode;
    }

    public ArenaMap map() {
        return map;
    }

    public State state() {
        return state;
    }

    public boolean suddenDeath() {
        return suddenDeath;
    }

    public Map<UUID, Fighter> fighters() {
        return fighters;
    }

    /** The fighters still on the server. */
    public List<ServerPlayer> online() {
        List<ServerPlayer> online = new ArrayList<>();
        for (Fighter fighter : fighters.values()) {
            if (fighter.present() && level.getServer().getPlayerList().getPlayer(fighter.id()) instanceof ServerPlayer player) {
                online.add(player);
            }
        }
        return online;
    }

    // ---- Flow ----

    /** Puts everyone on their teams and spawns, frozen, with the HUD up. */
    void begin() {
        makeTeams();
        Map<UUID, Spot> spots = rules.startSpots(this);
        for (ServerPlayer player : online()) {
            Spot spot = spots.get(player.getUUID());
            Players.refresh(player);
            place(player, spot);
            freeze(player, spot);
            bossBar.addPlayer(player);
            sidebar.show(player);
        }
        updateHud();
    }

    void tick() {
        tick++;
        stateTicks++;
        switch (state) {
            case STARTING -> tickStarting();
            case PLAYING -> tickPlaying();
            case ENDED -> {
                if (stateTicks >= ArenaConfig.ticks(ArenaConfig.END_SECONDS)) {
                    finish();
                }
            }
        }
    }

    private void tickStarting() {
        int freeze = ArenaConfig.ticks(ArenaConfig.FREEZE_SECONDS);
        for (ServerPlayer player : online()) {
            Spot spot = frozenAt.get(player.getUUID());
            if (spot != null && player.position().distanceToSqr(spot.pos()) > FREEZE_SLACK * FREEZE_SLACK) {
                player.connection.teleport(spot.pos().x, spot.pos().y, spot.pos().z, player.getYRot(), player.getXRot());
            }
        }
        if (stateTicks >= freeze) {
            state = State.PLAYING;
            stateTicks = 0;
            timeLeft = ArenaConfig.timeLimitTicks(mode);
            for (ServerPlayer player : online()) {
                unfreeze(player);
                Titles.show(player, Component.literal("Fight!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.empty(), 20);
                player.playNotifySound(SoundEvents.ENDER_DRAGON_GROWL, SoundSource.MASTER, 0.4f, 1.4f);
            }
            updateHud();
        } else if ((freeze - stateTicks) % 20 == 0 || stateTicks == 1) {
            int seconds = (freeze - stateTicks + 19) / 20;
            for (ServerPlayer player : online()) {
                Titles.show(player, Component.literal(String.valueOf(seconds)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                        Component.literal(mode.displayName() + " on " + map.name()), 22);
                player.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.MASTER, 1.0f, 1.0f);
            }
        }
    }

    private void tickPlaying() {
        // Falling out of a void arena counts as a death, credited to whoever last hit them.
        map.floor().ifPresent(floor -> {
            for (ServerPlayer player : online()) {
                if (player.getY() < floor) {
                    died(player, player.damageSources().fellOutOfWorld());
                }
            }
        });
        if (scoresChanged) {
            scoresChanged = false;
            Optional<ModeRules.Result> won = rules.decided(this);
            if (won.isPresent()) {
                end(won.get());
                return;
            }
        }
        if (--timeLeft <= 0) {
            Optional<ModeRules.Result> timeUp = rules.timeUp(this, suddenDeath || ArenaConfig.SUDDEN_DEATH_SECONDS.get() == 0);
            if (timeUp.isPresent()) {
                end(timeUp.get());
                return;
            }
            suddenDeath = true;
            timeLeft = ArenaConfig.ticks(ArenaConfig.SUDDEN_DEATH_SECONDS);
            for (ServerPlayer player : online()) {
                Titles.show(player, Component.literal("Sudden death").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                        Component.literal("The next kill to break the tie wins"), 40);
            }
        }
        if (stateTicks % 20 == 0) {
            updateHud();
        }
    }

    private void end(ModeRules.Result result) {
        this.result = result;
        state = State.ENDED;
        stateTicks = 0;
        MutableComponent summary = Component.literal(mode.displayName() + " on " + map.name() + ": ").withStyle(ChatFormatting.GOLD)
                .append(result.headline().copy());
        fighters.values().stream()
                .sorted(Comparator.comparingInt(Fighter::kills).reversed().thenComparingInt(Fighter::deaths))
                .forEach(fighter -> summary.append(Component.literal("\n  ")).append(name(fighter))
                        .append(Component.literal(": " + fighter.kills() + " kills, " + fighter.deaths() + " deaths").withStyle(ChatFormatting.GRAY)));
        for (ServerPlayer player : online()) {
            Titles.show(player, result.headline(), result.detail(), ArenaConfig.ticks(ArenaConfig.END_SECONDS));
            boolean won = result.winners().contains(player.getUUID());
            player.playNotifySound(won ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.MASTER, 1.0f, 1.0f);
            player.sendSystemMessage(summary);
        }
        updateHud();
    }

    /** Ends the match at once with no winner (an operator's /arena stop, or the server stopping). */
    void stop() {
        for (ServerPlayer player : online()) {
            player.sendSystemMessage(Component.literal("The match was stopped").withStyle(ChatFormatting.YELLOW));
        }
        finish();
    }

    /** Everyone back to the lobby; the map is free again. */
    private void finish() {
        state = State.ENDED;
        List<ServerPlayer> players = online();
        fighters.values().forEach(Fighter::leave);
        Matches.finished(this);
        bossBar.removeAllPlayers();
        removeTeams();
        for (ServerPlayer player : players) {
            unfreeze(player);
            sidebar.hide(player);
            Lobby.send(player);
        }
    }

    /** A fighter leaving the server, or taken out of the match: they're done, and the rules may now call it. */
    void remove(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        if (fighter == null || !fighter.present()) {
            return;
        }
        fighter.leave();
        scoresChanged = true;
        unfreeze(player);
        leaveTeam(player);
        bossBar.removePlayer(player);
        sidebar.hide(player);
        if (state == State.PLAYING) {
            updateHud();
        }
    }

    // ---- Deaths and damage ----

    /** True if this fighter can't be hurt right now: before the fight, after it, or with spawn protection. */
    boolean shielded(ServerPlayer player) {
        Fighter fighter = fighters.get(player.getUUID());
        return state != State.PLAYING || fighter == null || fighter.protectedAt(tick);
    }

    /**
     * Instead of dying: a death for them, a kill for whoever downed them (through Vis Mod's owner chain, so a summon's
     * kill is its summoner's; or whoever hit them last, for a fall), then a fresh respawn.
     */
    void died(ServerPlayer victim, DamageSource source) {
        Fighter fighter = fighters.get(victim.getUUID());
        if (fighter == null) {
            return;
        }
        if (state != State.PLAYING) {
            // Only something unstoppable (the void, /kill) gets here: put them back.
            Spot spot = frozenAt.getOrDefault(victim.getUUID(), rules.respawnSpot(this, victim));
            Players.refresh(victim);
            place(victim, spot);
            return;
        }
        fighter.addDeath();
        // No credit for downing yourself or a teammate.
        Optional<Fighter> killer = killer(victim, source)
                .filter(other -> other != fighter && (other.team().isEmpty() || !other.team().equals(fighter.team())));
        killer.ifPresent(Fighter::addKill);
        scoresChanged = true;
        Component message = killer.map(k -> name(k)
                        .append(Component.literal(" eliminated ").withStyle(ChatFormatting.GRAY))
                        .append(name(fighter)))
                .orElse(name(fighter).append(Component.literal(" died").withStyle(ChatFormatting.GRAY)));
        for (ServerPlayer player : online()) {
            player.sendSystemMessage(message);
        }
        killer.flatMap(k -> online().stream().filter(p -> p.getUUID().equals(k.id())).findFirst()).ifPresent(k -> {
            k.displayClientMessage(Component.literal("Eliminated " + fighter.name()).withStyle(ChatFormatting.GOLD), true);
            k.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 1.0f, 0.8f);
        });
        Titles.show(victim, Component.literal("Eliminated").withStyle(ChatFormatting.RED),
                killer.map(k -> Component.literal("by " + k.name())).orElse(Component.empty()), 30);
        Players.refresh(victim);
        place(victim, rules.respawnSpot(this, victim));
        fighter.setProtectedUntil(tick + ArenaConfig.ticks(ArenaConfig.SPAWN_PROTECTION_SECONDS));
        updateHud();
    }

    private Optional<Fighter> killer(ServerPlayer victim, DamageSource source) {
        Entity attacker = source.getEntity() != null ? source.getEntity() : victim.getKillCredit();
        if (attacker == null) {
            return Optional.empty();
        }
        Entity owner = VisAllies.owner(attacker);
        Fighter fighter = fighters.get(owner.getUUID());
        return fighter != null && fighter.present() ? Optional.of(fighter) : Optional.empty();
    }

    // ---- Players ----

    /** A fighter's name, in their team's colour (white without one). */
    public static MutableComponent name(Fighter fighter) {
        return Component.literal(fighter.name()).withStyle(fighter.team().map(TeamColor::color).orElse(ChatFormatting.WHITE));
    }

    // ---- Teams ----

    /**
     * In a team mode, a vanilla scoreboard team per side for this match: coloured names, friendly fire off (which Vis
     * Mod's ally check reads, so teammates' abilities spare each other), name tags hidden from the other team.
     */
    private void makeTeams() {
        if (!mode.teams()) {
            return;
        }
        ServerScoreboard scoreboard = level.getServer().getScoreboard();
        for (TeamColor color : TeamColor.values()) {
            String name = TEAM_PREFIX + id + "_" + color.id();
            PlayerTeam stale = scoreboard.getPlayerTeam(name);
            if (stale != null) {
                scoreboard.removePlayerTeam(stale);
            }
            PlayerTeam team = scoreboard.addPlayerTeam(name);
            team.setDisplayName(Component.literal(color.displayName()));
            team.setColor(color.color());
            team.setAllowFriendlyFire(false);
            team.setSeeFriendlyInvisibles(true);
            team.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
            teams.put(color, team);
        }
        for (Fighter fighter : fighters.values()) {
            fighter.team().ifPresent(color -> scoreboard.addPlayerToTeam(fighter.name(), teams.get(color)));
        }
    }

    private void leaveTeam(ServerPlayer player) {
        PlayerTeam team = level.getServer().getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team != null && teams.containsValue(team)) {
            level.getServer().getScoreboard().removePlayerFromTeam(player.getScoreboardName(), team);
        }
    }

    private void removeTeams() {
        teams.values().forEach(team -> level.getServer().getScoreboard().removePlayerTeam(team));
        teams.clear();
    }

    /** Teams left behind by matches that never finished (the server crashed or was killed mid-match). */
    static void removeLeftoverTeams(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        List.copyOf(scoreboard.getPlayerTeams()).stream()
                .filter(team -> team.getName().startsWith(TEAM_PREFIX))
                .forEach(scoreboard::removePlayerTeam);
    }

    private void place(ServerPlayer player, Spot spot) {
        player.teleportTo(level, spot.pos().x, spot.pos().y, spot.pos().z, spot.yaw(), spot.pitch());
    }

    private void freeze(ServerPlayer player, Spot spot) {
        frozenAt.put(player.getUUID(), spot);
        AttributeModifier stop = new AttributeModifier(FREEZE, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (speed != null && !speed.hasModifier(FREEZE)) {
            speed.addTransientModifier(stop);
        }
        if (jump != null && !jump.hasModifier(FREEZE)) {
            jump.addTransientModifier(stop);
        }
    }

    private void unfreeze(ServerPlayer player) {
        frozenAt.remove(player.getUUID());
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (speed != null) {
            speed.removeModifier(FREEZE);
        }
        if (jump != null) {
            jump.removeModifier(FREEZE);
        }
    }

    // ---- HUD ----

    private void updateHud() {
        List<ServerPlayer> viewers = online();
        String time = clock(state == State.STARTING ? ArenaConfig.timeLimitTicks(mode) : timeLeft);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(suddenDeath ? "Sudden death " + time : "Time " + time)
                .withStyle(suddenDeath ? ChatFormatting.RED : ChatFormatting.WHITE));
        lines.add(Component.literal(" "));
        lines.addAll(rules.scoreLines(this));
        sidebar.update(lines, viewers);

        switch (state) {
            case STARTING -> {
                bossBar.setName(Component.literal(mode.displayName() + " on " + map.name() + " - get ready"));
                bossBar.setProgress(1);
            }
            case PLAYING -> {
                int total = suddenDeath ? ArenaConfig.ticks(ArenaConfig.SUDDEN_DEATH_SECONDS) : ArenaConfig.timeLimitTicks(mode);
                bossBar.setName(Component.literal((suddenDeath ? "Sudden death " : mode.displayName() + "  ") + time));
                bossBar.setColor(suddenDeath ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
                bossBar.setProgress(Math.clamp(timeLeft / (float) Math.max(1, total), 0f, 1f));
            }
            case ENDED -> {
                bossBar.setName(result != null ? result.headline() : Component.literal("Match over"));
                bossBar.setColor(BossEvent.BossBarColor.GREEN);
                bossBar.setProgress(1);
            }
        }
    }

    private static String clock(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
