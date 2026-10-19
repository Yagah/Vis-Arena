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

    /** Each mode's fewest and most players. */
    public static final Map<ArenaMode, ModeCounts> COUNTS = new EnumMap<>(ArenaMode.class);

    public record ModeCounts(ModConfigSpec.IntValue min, ModConfigSpec.IntValue max) {
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
            COUNTS.put(mode, new ModeCounts(min, max));
            BUILDER.pop();
        }
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ArenaConfig() {
    }

    public static int minPlayers(ArenaMode mode) {
        return COUNTS.get(mode).min().get();
    }

    public static int maxPlayers(ArenaMode mode) {
        return Math.max(minPlayers(mode), COUNTS.get(mode).max().get());
    }
}
