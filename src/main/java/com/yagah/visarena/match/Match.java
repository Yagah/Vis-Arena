package com.yagah.visarena.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.OptionalInt;
import java.util.UUID;

import javax.annotation.Nullable;

import com.yagah.vismod.data.VisDataSync;
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
import com.yagah.visarena.stats.ArenaStats;

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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * One match on one map: the same flow for every mode. Starting (players frozen at their spawns), playing (the mode's
 * rules keep score until someone wins or time runs out, with sudden death on a tie), ended (the winner shown), then
 * everyone back to the lobby. Deaths never show the death screen: the fighter is healed and respawned on the spot the
 * rules pick, with a moment of spawn protection.
 */
public final class Match {
    public enum State { STARTING, PLAYING, ROUND_OVER, ENDED }

    private static final ResourceLocation FREEZE = ResourceLocation.fromNamespaceAndPath(VisArena.MODID, "freeze");
    private static final ResourceLocation UNDERDOG = ResourceLocation.fromNamespaceAndPath(VisArena.MODID, "underdog");
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
    /** The short side in a team match with uneven teams (empty when even). */
    private final Set<TeamColor> underdogs = EnumSet.noneOf(TeamColor.class);
    private final ServerBossEvent bossBar;
    private final Sidebar sidebar;

    private State state = State.STARTING;
    /** Ticks since the match began (spawn protection counts in these). */
    private int tick;
    /** Ticks since the current state began. */
    private int stateTicks;
    private int timeLeft;
    private boolean suddenDeath;
    /** The round being played, from 1 (round modes only). */
    private int round = 1;
    /** Set when a kill or a departure may have decided the match, so the rules are asked only then. */
    private boolean scoresChanged = true;
    @Nullable
    private ModeRules.Result result;

    /** A match of these players; in team modes, sides holds each one's team (from TeamDeal). */
    Match(ArenaMode mode, ArenaMap map, ServerLevel level, ModeRules rules, List<ServerPlayer> players, Map<UUID, TeamColor> sides) {
        this.mode = mode;
        this.map = map;
        this.level = level;
        this.rules = rules;
        players.forEach(player -> fighters.put(player.getUUID(), new Fighter(player.getUUID(), player.getGameProfile().getName())));
        sides.forEach((id, team) -> {
            Fighter fighter = fighters.get(id);
            if (fighter != null) {
                fighter.setTeam(team);
            }
        });
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

    /** The round being played, from 1 (round modes). */
    public int round() {
        return round;
    }

    public Map<UUID, Fighter> fighters() {
        return fighters;
    }

    public ServerLevel level() {
        return level;
    }

    /** Ticks since the match began. */
    public int ticks() {
        return tick;
    }

    /** Ticks played of the current fight (or round), counting from its "Fight!". */
    public int elapsed() {
        return state == State.PLAYING ? ArenaConfig.timeLimitTicks(mode) - timeLeft : 0;
    }

    /** The vanilla team for a side, in team modes. */
    public Optional<PlayerTeam> team(TeamColor color) {
        return Optional.ofNullable(teams.get(color));
    }

    /** The fighter for a player, if they're in this match. */
    public Optional<Fighter> fighter(Entity entity) {
        return Optional.ofNullable(fighters.get(entity.getUUID()));
    }

    /** Has the rules check for a decided round or match on the next tick (a mode's own event changed the score). */
    public void requestCheck() {
        scoresChanged = true;
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
        updateUnderdogs();
        for (ServerPlayer player : online()) {
            bossBar.addPlayer(player);
            sidebar.show(player);
        }
        placeAll();
        updateHud();
    }

    /** Everyone fresh and frozen on their start spots, for the countdown to the fight (or to the next round). */
    private void placeAll() {
        Map<UUID, Spot> spots = rules.startSpots(this);
        for (ServerPlayer player : online()) {
            Spot spot = spots.get(player.getUUID());
            Players.refresh(player);
            Fighter fighter = fighters.get(player.getUUID());
            fighter.setOut(false);
            fighter.setRespawnAt(0);
            place(player, spot);
            freeze(player, spot);
        }
        rules.roundStarted(this);
    }

    void tick() {
        tick++;
        stateTicks++;
        switch (state) {
            case STARTING -> tickStarting();
            case PLAYING -> tickPlaying();
            case ROUND_OVER -> {
                if (stateTicks >= ArenaConfig.ticks(ArenaConfig.ROUND_PAUSE_SECONDS)) {
                    round++;
                    state = State.STARTING;
                    stateTicks = 0;
                    placeAll();
                    updateHud();
                }
            }
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
                Players.holdAt(player, spot.pos());
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
                        Component.literal(rules.rounds() ? rules.roundName() + " " + round : mode.displayName() + " on " + map.name()), 22);
                player.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.MASTER, 1.0f, 1.0f);
            }
        }
    }

    private void tickPlaying() {
        // Falling out of a void arena counts as a death, credited to whoever last hit them.
        map.floor().ifPresent(floor -> {
            for (ServerPlayer player : online()) {
                if (player.getY() < floor && !fighters.get(player.getUUID()).out()) {
                    died(player, player.damageSources().fellOutOfWorld());
                }
            }
        });
        // Fighters waiting out a respawn delay come back once it's up, with a countdown on the action bar meanwhile.
        for (ServerPlayer player : online()) {
            Fighter fighter = fighters.get(player.getUUID());
            if (fighter.respawnAt() > 0 && tick >= fighter.respawnAt()) {
                respawn(player, fighter);
            } else if (fighter.respawnAt() > 0 && (fighter.respawnAt() - tick) % 20 == 0) {
                player.displayClientMessage(Component.literal("Respawning in " + (fighter.respawnAt() - tick) / 20)
                        .withStyle(ChatFormatting.YELLOW), true);
            }
        }
        if (tick % 20 == 0 && !underdogs.isEmpty()) {
            underdogVis();
        }
        rules.tick(this);
        if (state != State.PLAYING) {
            return;
        }
        if (scoresChanged) {
            scoresChanged = false;
            Optional<ModeRules.RoundEnd> roundEnd = rules.rounds() ? rules.roundOver(this, false) : Optional.empty();
            if (roundEnd.isPresent()) {
                endRound(roundEnd.get());
                return;
            }
            Optional<ModeRules.Result> won = rules.decided(this);
            if (won.isPresent()) {
                end(won.get());
                return;
            }
        }
        if (--timeLeft <= 0 && rules.rounds()) {
            endRound(rules.roundOver(this, true).orElseThrow());
            return;
        }
        if (timeLeft <= 0) {
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

    /** A round is over: a point to its winner, then the match's end if that decided it, or a pause before the next. */
    private void endRound(ModeRules.RoundEnd roundEnd) {
        roundEnd.winner().map(fighters::get).ifPresent(Fighter::addRound);
        Optional<ModeRules.Result> won = rules.decided(this);
        if (won.isPresent()) {
            end(won.get());
            return;
        }
        state = State.ROUND_OVER;
        stateTicks = 0;
        Component score = rules.roundSubtitle(this).orElseGet(() -> {
            MutableComponent rounds = Component.empty();
            for (Fighter fighter : fighters.values()) {
                if (!rounds.getSiblings().isEmpty()) {
                    rounds.append(Component.literal("  -  ").withStyle(ChatFormatting.GRAY));
                }
                rounds.append(name(fighter)).append(Component.literal(" " + fighter.roundsWon()).withStyle(ChatFormatting.GOLD));
            }
            return rounds;
        });
        for (ServerPlayer player : online()) {
            Titles.show(player, roundEnd.headline(), score, ArenaConfig.ticks(ArenaConfig.ROUND_PAUSE_SECONDS));
            boolean tookIt = roundEnd.winner().equals(Optional.of(player.getUUID()));
            player.playNotifySound(tookIt ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.MASTER, 0.8f, 1.0f);
        }
        updateHud();
    }

    private void end(ModeRules.Result result) {
        this.result = result;
        state = State.ENDED;
        stateTicks = 0;
        // Everyone who played counts, leavers included (as a game not won). A stopped match isn't recorded.
        ArenaStats stats = ArenaStats.get(level.getServer());
        for (Fighter fighter : fighters.values()) {
            stats.add(fighter.id(), fighter.name(), mode, result.winners().contains(fighter.id()), fighter.kills(), fighter.deaths());
        }
        MutableComponent summary = Component.literal(mode.displayName() + " on " + map.name() + ": ").withStyle(ChatFormatting.GOLD)
                .append(result.headline().copy());
        fighters.values().stream()
                .sorted(Comparator.comparingInt(Fighter::kills).reversed().thenComparingInt(Fighter::deaths))
                .forEach(fighter -> summary.append(Component.literal("\n  ")).append(name(fighter))
                        .append(Component.literal(": " + (rules.rounds() ? fighter.roundsWon() + " rounds, " : "")
                                + fighter.kills() + " kills, " + fighter.deaths() + " deaths").withStyle(ChatFormatting.GRAY)));
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
        rules.finished(this);
        removeTeams();
        for (ServerPlayer player : players) {
            setUnderdogHealth(player, false);
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
        setUnderdogHealth(player, false);
        updateUnderdogs();
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
        return state != State.PLAYING || fighter == null || fighter.out() || fighter.protectedAt(tick);
    }

    /** True if this fighter is out of the current round, so nothing they do can hurt anyone. */
    boolean benched(Entity entity) {
        Fighter fighter = fighters.get(entity.getUUID());
        return fighter != null && fighter.out();
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
        scoresChanged = true;
        OptionalInt delay = rules.respawnDelay(this, fighter);
        if (delay.isEmpty() || delay.getAsInt() > 0) {
            // Out for a while (or the rest of the round): watching, where they fell.
            fighter.setOut(true);
            fighter.setRespawnAt(delay.isPresent() ? tick + delay.getAsInt() : 0);
            Players.refresh(victim);
            victim.setGameMode(GameType.SPECTATOR);
            updateHud();
            return;
        }
        respawn(victim, fighter);
    }

    /** Back in the fight: fresh, on the spawn the rules pick, with spawn protection. */
    private void respawn(ServerPlayer victim, Fighter fighter) {
        fighter.setOut(false);
        fighter.setRespawnAt(0);
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
    /** True if this fighter's team is the short one (uneven team sizes), and so gets the underdog bonus. */
    public boolean underdog(Fighter fighter) {
        return fighter.team().isPresent() && underdogs.contains(fighter.team().get());
    }

    /**
     * In team modes with uneven sides (counting players still here), the short team gets the underdog bonus: extra max
     * health now, extra Vis every second, and (in Core Siege) quicker respawns. Rechecked whenever someone leaves.
     */
    private void updateUnderdogs() {
        if (!mode.teams()) {
            return;
        }
        Map<TeamColor, Integer> sizes = new EnumMap<>(TeamColor.class);
        for (TeamColor team : TeamColor.values()) {
            sizes.put(team, (int) fighters.values().stream().filter(f -> f.present() && f.team().equals(Optional.of(team))).count());
        }
        int red = sizes.get(TeamColor.RED);
        int blue = sizes.get(TeamColor.BLUE);
        Set<TeamColor> next = red == blue ? Set.of() : Set.of(red < blue ? TeamColor.RED : TeamColor.BLUE);
        if (next.equals(underdogs)) {
            return;
        }
        underdogs.clear();
        underdogs.addAll(next);
        for (ServerPlayer player : online()) {
            boolean bonus = underdog(fighters.get(player.getUUID()));
            setUnderdogHealth(player, bonus);
            if (bonus) {
                player.sendSystemMessage(Component.literal("Your team is a player short. Underdog bonus: +"
                        + ArenaConfig.UNDERDOG_HEALTH.get() / 2.0 + " hearts, +" + ArenaConfig.UNDERDOG_VIS_PER_SECOND.get() + " Vis a second"
                        + (mode == ArenaMode.CORE_SIEGE ? ", quicker respawns" : "")).withStyle(ChatFormatting.AQUA));
            }
        }
    }

    /** Once a second: the short team's extra Vis (not while waiting to respawn). */
    private void underdogVis() {
        float vis = ArenaConfig.UNDERDOG_VIS_PER_SECOND.get().floatValue();
        if (vis <= 0) {
            return;
        }
        for (ServerPlayer player : online()) {
            Fighter fighter = fighters.get(player.getUUID());
            if (underdog(fighter) && !fighter.out()) {
                VisDataSync.get(player).addEnergy(vis);
                VisDataSync.syncEnergyIfChanged(player);
            }
        }
    }

    private static void setUnderdogHealth(ServerPlayer player, boolean bonus) {
        AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) {
            return;
        }
        health.removeModifier(UNDERDOG);
        if (bonus && ArenaConfig.UNDERDOG_HEALTH.get() > 0) {
            health.addTransientModifier(new AttributeModifier(UNDERDOG, ArenaConfig.UNDERDOG_HEALTH.get(), AttributeModifier.Operation.ADD_VALUE));
        }
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

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
        Players.teleport(player, level, spot);
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
        lines.add(Component.literal(suddenDeath ? "Sudden death " + time : (rules.rounds() ? rules.roundName() + " " + round + "  " : "Time ") + time)
                .withStyle(suddenDeath ? ChatFormatting.RED : ChatFormatting.WHITE));
        lines.add(Component.literal(" "));
        lines.addAll(rules.scoreLines(this));
        sidebar.update(lines, viewers);

        switch (state) {
            case STARTING -> {
                bossBar.setName(Component.literal((rules.rounds() ? mode.displayName() + " " + rules.roundName().toLowerCase() + " " + round : mode.displayName() + " on " + map.name()) + " - get ready"));
                bossBar.setProgress(1);
            }
            case PLAYING -> {
                int total = suddenDeath ? ArenaConfig.ticks(ArenaConfig.SUDDEN_DEATH_SECONDS) : ArenaConfig.timeLimitTicks(mode);
                bossBar.setName(Component.literal((suddenDeath ? "Sudden death " : mode.displayName() + (rules.rounds() ? " " + rules.roundName().toLowerCase() + " " + round : "") + "  ") + time));
                bossBar.setColor(suddenDeath ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
                bossBar.setProgress(Math.clamp(timeLeft / (float) Math.max(1, total), 0f, 1f));
            }
            case ROUND_OVER -> {
                bossBar.setName(Component.literal(mode.displayName() + " " + rules.roundName().toLowerCase() + " " + round + " over"));
                bossBar.setProgress(1);
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
