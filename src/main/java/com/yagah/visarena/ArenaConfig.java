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
                    .comment("Score that wins a " + mode.displayName() + " match outright (kills, team kills or rounds).")
                    .defineInRange("scoreLimit", mode.defaultScoreLimit(), 1, 1000);
            ModConfigSpec.IntValue timeLimit = BUILDER
                    .comment("Seconds a " + mode.displayName() + " match (or round) lasts.")
                    .defineInRange("timeLimitSeconds", mode.defaultTimeLimitSeconds(), 10, 7200);
            MODES.put(mode, new ModeSettings(min, max, scoreLimit, timeLimit));
            BUILDER.pop();
        }
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
