package com.yagah.visarena;

import java.util.EnumMap;
import java.util.Map;

import com.yagah.visarena.mode.ArenaMode;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Arena settings. A COMMON config (config/visarena-common.toml), since SERVER configs are synced to clients and players
 * don't have this mod.
 */
public final class ArenaConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("match");
    }

    public static final ModConfigSpec.IntValue COUNTDOWN_SECONDS = BUILDER
            .comment("Seconds from a queue reaching its fewest players to the match starting.")
            .defineInRange("countdownSeconds", 15, 1, 600);

    public static final ModConfigSpec.IntValue FULL_COUNTDOWN_SECONDS = BUILDER
            .comment("The countdown shortens to this once the queue holds the most players a match takes.")
            .defineInRange("fullCountdownSeconds", 5, 1, 600);

    public static final ModConfigSpec.IntValue TEAM_COUNTDOWN_SECONDS = BUILDER
            .comment("The countdown in team modes, long enough to pick a side on the team screen. It doesn't shorten when full.")
            .defineInRange("teamCountdownSeconds", 20, 1, 600);

    public static final ModConfigSpec.IntValue FREEZE_SECONDS = BUILDER
            .comment("Seconds players stand frozen at their spawns before the fight starts.")
            .defineInRange("freezeSeconds", 3, 0, 60);

    public static final ModConfigSpec.IntValue SPAWN_PROTECTION_SECONDS = BUILDER
            .comment("Seconds a respawned player can't be hurt.")
            .defineInRange("spawnProtectionSeconds", 3, 0, 60);

    public static final ModConfigSpec.IntValue END_SECONDS = BUILDER
            .comment("Seconds the winner is shown before everyone goes back to the lobby.")
            .defineInRange("endSeconds", 10, 1, 120);

    public static final ModConfigSpec.IntValue ROUND_PAUSE_SECONDS = BUILDER
            .comment("In round modes (1v1), seconds the round's result is shown before the next round's countdown.")
            .defineInRange("roundPauseSeconds", 3, 1, 60);

    public static final ModConfigSpec.IntValue SUDDEN_DEATH_SECONDS = BUILDER
            .comment("On a tie when time runs out, seconds of sudden death (the next kill to break the tie wins) before a draw.")
            .defineInRange("suddenDeathSeconds", 60, 0, 600);

    static {
        BUILDER.pop();
        BUILDER.push("underdog");
    }

    public static final ModConfigSpec.IntValue UNDERDOG_HEALTH = BUILDER
            .comment("When team sizes are uneven, extra max health (2 = one heart) for each player on the short team.")
            .defineInRange("extraHealth", 4, 0, 100);

    public static final ModConfigSpec.DoubleValue UNDERDOG_VIS_PER_SECOND = BUILDER
            .comment("When team sizes are uneven, extra Vis per second for each player on the short team.")
            .defineInRange("visPerSecond", 1.0, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue UNDERDOG_RESPAWN_MULTIPLIER = BUILDER
            .comment("When team sizes are uneven, the short team's respawn delays (Core Siege) are multiplied by this.")
            .defineInRange("respawnMultiplier", 0.6, 0.0, 1.0);

    static {
        BUILDER.pop();
    }

    /** Each mode's player counts, score limit and time limit. */
    public static final Map<ArenaMode, ModeSettings> MODES = new EnumMap<>(ArenaMode.class);

    public record ModeSettings(ModConfigSpec.IntValue min, ModConfigSpec.IntValue max, ModConfigSpec.IntValue scoreLimit,
            ModConfigSpec.IntValue timeLimit) {
    }

    static {
        for (ArenaMode mode : ArenaMode.values()) {
            BUILDER.push(mode.id());
            ModConfigSpec.IntValue min = BUILDER
                    .comment("Fewest players to start a " + mode.displayName() + " match.")
                    .defineInRange("minPlayers", mode.defaultMin(), 2, 64);
            ModConfigSpec.IntValue max = BUILDER
                    .comment("Most players in one " + mode.displayName() + " match.")
                    .defineInRange("maxPlayers", mode.defaultMax(), 2, 64);
            ModConfigSpec.IntValue scoreLimit = BUILDER
                    .comment(switch (mode) {
                        case FFA -> "Not used: FFA's kill target grows with the players (see [ffa_rules]).";
                        case TDM -> "Not used: see teamKillTarget in [tdm_rules].";
                        case DUEL -> "Rounds that win a 1v1.";
                        case CORE_SIEGE -> "Not used: Core Siege is won on time and damage (see [core_siege_rules]).";
                        case PAYLOAD -> "Not used: Payload is won on time and distance (see [payload_rules]).";
                    })
                    .defineInRange("scoreLimit", mode.defaultScoreLimit(), 1, 1000);
            ModConfigSpec.IntValue timeLimit = BUILDER
                    .comment("Seconds a " + mode.displayName() + " match (or round) lasts.")
                    .defineInRange("timeLimitSeconds", mode.defaultTimeLimitSeconds(), 10, 7200);
            MODES.put(mode, new ModeSettings(min, max, scoreLimit, timeLimit));
            BUILDER.pop();
        }
    }

    static {
        BUILDER.push("ffa_rules");
    }

    public static final ModConfigSpec.IntValue FFA_KILLS_PER_PLAYER = BUILDER
            .comment("FFA is first to this many kills per player in the match (2: 2 players play to 4, 3 to 6, 4 to 8).")
            .defineInRange("killsPerPlayer", 2, 1, 100);

    static {
        BUILDER.pop();
        BUILDER.push("tdm_rules");
    }

    public static final ModConfigSpec.IntValue TDM_KILL_TARGET = BUILDER
            .comment("Team kills that win a Team Deathmatch, whatever the team sizes.")
            .defineInRange("teamKillTarget", 10, 1, 1000);

    static {
        BUILDER.pop();
        BUILDER.push("duel_rules");
    }

    public static final ModConfigSpec.IntValue DUEL_DRAWS_TO_END = BUILDER
            .comment("A 1v1 with this many drawn rounds (nobody downed) ends as no contest: back to the lobby, nothing recorded.")
            .defineInRange("drawsToEnd", 3, 1, 100);

    static {
        BUILDER.pop();
        BUILDER.push("payload_rules");
    }

    public static final ModConfigSpec.DoubleValue PAYLOAD_RADIUS = BUILDER
            .comment("Blocks from the payload within which players push or contest it (and attackers are healed).")
            .defineInRange("pushRadius", 5.5, 0.5, 32.0);

    public static final ModConfigSpec.DoubleValue PAYLOAD_SPEED = BUILDER
            .comment("Blocks per second the payload moves with one attacker pushing.")
            .defineInRange("speed", 1.5, 0.05, 20.0);

    public static final ModConfigSpec.DoubleValue PAYLOAD_TWO_PUSHERS = BUILDER
            .comment("Speed multiplier with two attackers pushing.")
            .defineInRange("twoPushersMultiplier", 1.25, 1.0, 10.0);

    public static final ModConfigSpec.DoubleValue PAYLOAD_THREE_PUSHERS = BUILDER
            .comment("Speed multiplier with three or more attackers pushing (the cap).")
            .defineInRange("threePushersMultiplier", 1.5, 1.0, 10.0);

    public static final ModConfigSpec.IntValue PAYLOAD_ROLLBACK_DELAY_SECONDS = BUILDER
            .comment("Seconds with no attacker near before the payload starts rolling back (never past a checkpoint).")
            .defineInRange("rollbackDelaySeconds", 8, 1, 600);

    public static final ModConfigSpec.DoubleValue PAYLOAD_ROLLBACK_SPEED = BUILDER
            .comment("Blocks per second the payload rolls back.")
            .defineInRange("rollbackSpeed", 0.5, 0.0, 20.0);

    public static final ModConfigSpec.DoubleValue PAYLOAD_HEAL_PER_SECOND = BUILDER
            .comment("Health per second for each attacker near the payload.")
            .defineInRange("healPerSecond", 1.0, 0.0, 100.0);

    public static final ModConfigSpec.IntValue PAYLOAD_CHECKPOINT_SECONDS = BUILDER
            .comment("Seconds added to the clock when the attackers reach a checkpoint.")
            .defineInRange("checkpointBonusSeconds", 60, 0, 3600);

    public static final ModConfigSpec.DoubleValue PAYLOAD_OVERTIME_GRACE_SECONDS = BUILDER
            .comment("In overtime, how long the payload may go without an attacker near before the defenders win.")
            .defineInRange("overtimeGraceSeconds", 1.0, 0.05, 60.0);

    public static final ModConfigSpec.IntValue PAYLOAD_ATTACKER_RESPAWN_SECONDS = BUILDER
            .comment("Seconds a downed attacker waits (spectating) before respawning.")
            .defineInRange("attackerRespawnSeconds", 5, 0, 120);

    public static final ModConfigSpec.IntValue PAYLOAD_DEFENDER_RESPAWN_SECONDS = BUILDER
            .comment("Seconds a downed defender waits (spectating) before respawning.")
            .defineInRange("defenderRespawnSeconds", 5, 0, 120);

    static {
        BUILDER.pop();
        BUILDER.push("core_siege_rules");
    }

    public static final ModConfigSpec.IntValue CORE_HEALTH = BUILDER
            .comment("The core's health each half.")
            .defineInRange("coreHealth", 400, 1, 100000);

    public static final ModConfigSpec.DoubleValue CORE_DAMAGE_MULTIPLIER = BUILDER
            .comment("Each hit on the core deals its damage times this.")
            .defineInRange("coreDamageMultiplier", 1.0, 0.01, 100.0);

    public static final ModConfigSpec.IntValue ATTACKER_RESPAWN_SECONDS = BUILDER
            .comment("Seconds a downed attacker waits (spectating) before respawning.")
            .defineInRange("attackerRespawnSeconds", 5, 0, 120);

    public static final ModConfigSpec.IntValue DEFENDER_RESPAWN_SECONDS = BUILDER
            .comment("Seconds a downed defender waits (spectating) before respawning.")
            .defineInRange("defenderRespawnSeconds", 5, 0, 120);

    public static final ModConfigSpec.DoubleValue NEAR_RADIUS = BUILDER
            .comment("Blocks around the core zone that count as near it (for shockwaves and the defenders' Vis bonus).")
            .defineInRange("nearRadius", 8.0, 0.0, 64.0);

    public static final ModConfigSpec.DoubleValue SHOCKWAVE_STRENGTH = BUILDER
            .comment("How hard the core's shockwave (at 75%, 50% and 25% health) throws nearby attackers.")
            .defineInRange("shockwaveStrength", 1.6, 0.0, 10.0);

    public static final ModConfigSpec.IntValue SHOCKWAVE_IMMUNITY_SECONDS = BUILDER
            .comment("Seconds the core can't be hurt after a shockwave.")
            .defineInRange("shockwaveImmunitySeconds", 2, 0, 60);

    public static final ModConfigSpec.DoubleValue DEFENDER_VIS_PER_SECOND = BUILDER
            .comment("Extra Vis per second for defenders near their core.")
            .defineInRange("defenderVisPerSecond", 2.0, 0.0, 100.0);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ArenaConfig() {
    }

    public static int minPlayers(ArenaMode mode) {
        return MODES.get(mode).min().get();
    }

    public static int maxPlayers(ArenaMode mode) {
        return Math.max(minPlayers(mode), MODES.get(mode).max().get());
    }

    public static int scoreLimit(ArenaMode mode) {
        return MODES.get(mode).scoreLimit().get();
    }

    public static int timeLimitTicks(ArenaMode mode) {
        return MODES.get(mode).timeLimit().get() * 20;
    }

    public static int ticks(ModConfigSpec.IntValue seconds) {
        return seconds.get() * 20;
    }
}
