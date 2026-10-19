package com.yagah.visarena.mode;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The game modes, with what a map needs to host each and how the menu shows it. */
public enum ArenaMode {
    FFA("ffa", "FFA", "Everyone for themselves. Most kills wins.", Items.IRON_SWORD, false, false, 2, 12, 15, 480),
    TDM("tdm", "Team Deathmatch", "Two teams. Most team kills wins.", Items.SHIELD, true, false, 4, 12, 30, 600),
    DUEL("duel", "1v1", "Two players, best of five rounds.", Items.GOLDEN_SWORD, false, false, 2, 2, 3, 120),
    CORE_SIEGE("core_siege", "Core Siege", "Two teams. Drain the enemy core, defend yours.", Items.BEACON, true, true, 4, 12, 1000, 720);

    private final String id;
    private final String displayName;
    private final String description;
    private final Item icon;
    private final boolean teams;
    private final boolean cores;
    private final int defaultMin;
    private final int defaultMax;
    private final int defaultScoreLimit;
    private final int defaultTimeLimitSeconds;

    ArenaMode(String id, String displayName, String description, Item icon, boolean teams, boolean cores, int defaultMin, int defaultMax,
            int defaultScoreLimit, int defaultTimeLimitSeconds) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.teams = teams;
        this.cores = cores;
        this.defaultMin = defaultMin;
        this.defaultMax = defaultMax;
        this.defaultScoreLimit = defaultScoreLimit;
        this.defaultTimeLimitSeconds = defaultTimeLimitSeconds;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Item icon() {
        return icon;
    }

    /** True if players play on two teams, so a map needs spawns for each. */
    public boolean teams() {
        return teams;
    }

    /** True if a map needs a core zone per team. */
    public boolean cores() {
        return cores;
    }

    public int defaultMin() {
        return defaultMin;
    }

    public int defaultMax() {
        return defaultMax;
    }

    public int defaultScoreLimit() {
        return defaultScoreLimit;
    }

    public int defaultTimeLimitSeconds() {
        return defaultTimeLimitSeconds;
    }

    /** How each built mode makes the rules for a new match; the others are still to come. */
    private static final Map<ArenaMode, Supplier<ModeRules>> RULES = Map.of(FFA, FfaRules::new, TDM, TdmRules::new, DUEL, DuelRules::new);

    /** The rules for a new match, or empty while the mode is still to come. */
    public Optional<ModeRules> rules() {
        return Optional.ofNullable(RULES.get(this)).map(Supplier::get);
    }

    /** True once the mode can be played (checked every tick, so it makes nothing). */
    public boolean playable() {
        return RULES.containsKey(this);
    }

    public static Optional<ArenaMode> byId(String id) {
        return Arrays.stream(values()).filter(mode -> mode.id.equals(id)).findFirst();
    }
}
