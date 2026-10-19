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
                    .comment(mode == ArenaMode.CORE_SIEGE ? "Not used: Core Siege is won on time and damage (see [core_siege_rules])."
                            : "Score that wins a " + mode.displayName() + " match outright (kills, team kills or rounds).")
                    .defineInRange("scoreLimit", mode.defaultScoreLimit(), 1, 1000);
            ModConfigSpec.IntValue timeLimit = BUILDER
                    .comment("Seconds a " + mode.displayName() + " match (or round) lasts.")
                    .defineInRange("timeLimitSeconds", mode.defaultTimeLimitSeconds(), 10, 7200);
            MODES.put(mode, new ModeSettings(min, max, scoreLimit, timeLimit));
            BUILDER.pop();
        }
    }

    static {
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

    public static final ModConfigSpec.IntValue REGEN_DELAY_SECONDS = BUILDER
            .comment("Seconds with no attacker near the core zone before the core starts to heal.")
            .defineInRange("regenDelaySeconds", 10, 1, 600);

    public static final ModConfigSpec.DoubleValue REGEN_PERCENT_PER_SECOND = BUILDER
            .comment("How fast the core heals, in percent of its health per second (never past its last shockwave mark).")
            .defineInRange("regenPercentPerSecond", 1.0, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue NEAR_RADIUS = BUILDER
            .comment("Blocks around the core zone that count as near it (for regeneration, shockwaves and the Vis bonus).")
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
