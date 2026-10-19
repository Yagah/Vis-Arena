package com.yagah.visarena.map;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * An arena: a place built by hand in one dimension, marked with /arena map commands. It lists the modes it hosts, its
 * spawns, the height below which a player has fallen out, and Core Siege's core zone.
 */
public final class ArenaMap {
    /**
     * A spawn point. Plain ones (and team ones) serve FFA and 1v1; one with a team serves only that team in TDM; one
     * with a siege side serves only that side in Core Siege.
     */
    public record Spawn(Spot spot, Optional<TeamColor> team, Optional<SiegeSide> side) {
        public static final Codec<Spawn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Spot.CODEC.fieldOf("spot").forGetter(Spawn::spot),
                TeamColor.CODEC.optionalFieldOf("team").forGetter(Spawn::team),
                SiegeSide.CODEC.optionalFieldOf("side").forGetter(Spawn::side)
        ).apply(instance, Spawn::new));
    }

    private static final Codec<ArenaMode> MODE_CODEC = Codec.STRING.xmap(
            id -> ArenaMode.byId(id).orElseThrow(), ArenaMode::id);

    public static final Codec<ArenaMap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(map -> map.name),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(map -> map.dimension),
            MODE_CODEC.listOf().fieldOf("modes").forGetter(map -> List.copyOf(map.modes)),
            Spawn.CODEC.listOf().fieldOf("spawns").forGetter(map -> map.spawns),
            Codec.INT.optionalFieldOf("floor").forGetter(map -> map.floor),
            BoundingBox.CODEC.optionalFieldOf("core").forGetter(map -> map.core)
    ).apply(instance, ArenaMap::new));

    private final String name;
    private final ResourceKey<Level> dimension;
    private final Set<ArenaMode> modes = EnumSet.noneOf(ArenaMode.class);
    private final List<Spawn> spawns;
    private Optional<Integer> floor;
    private Optional<BoundingBox> core;

    public ArenaMap(String name, ResourceKey<Level> dimension) {
        this(name, dimension, List.of(), List.of(), Optional.empty(), Optional.empty());
    }

    private ArenaMap(String name, ResourceKey<Level> dimension, List<ArenaMode> modes, List<Spawn> spawns,
            Optional<Integer> floor, Optional<BoundingBox> core) {
        this.name = name;
        this.dimension = dimension;
        this.modes.addAll(modes);
        this.spawns = new ArrayList<>(spawns);
        this.floor = floor;
        this.core = core;
    }

    public String name() {
        return name;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public Set<ArenaMode> modes() {
        return modes;
    }

    public List<Spawn> spawns() {
        return spawns;
    }

    /** The spawns FFA and 1v1 use: all but Core Siege's. */
    public List<Spawn> openSpawns() {
        return spawns.stream().filter(spawn -> spawn.side().isEmpty()).toList();
    }

    public List<Spawn> teamSpawns(TeamColor team) {
        return spawns.stream().filter(spawn -> spawn.team().equals(Optional.of(team))).toList();
    }

    public List<Spawn> siegeSpawns(SiegeSide side) {
        return spawns.stream().filter(spawn -> spawn.side().equals(Optional.of(side))).toList();
    }

    public Optional<Integer> floor() {
        return floor;
    }

    public void setFloor(Optional<Integer> floor) {
        this.floor = floor;
    }

    /** Core Siege's core zone: the core stands in the middle of its floor. */
    public Optional<BoundingBox> core() {
        return core;
    }

    public void setCore(Optional<BoundingBox> core) {
        this.core = core;
    }

    /** What this map still lacks to host the mode; empty once it is ready. */
    public List<String> missing(ArenaMode mode) {
        List<String> missing = new ArrayList<>();
        if (mode.cores()) {
            if (core.isEmpty()) {
                missing.add("a core zone");
            }
            for (SiegeSide side : SiegeSide.values()) {
                if (siegeSpawns(side).isEmpty()) {
                    missing.add("an " + side.id() + " spawn");
                }
            }
        } else if (mode.teams()) {
            for (TeamColor team : TeamColor.values()) {
                if (teamSpawns(team).isEmpty()) {
                    missing.add("a " + team.id() + " spawn");
                }
            }
        } else if (openSpawns().size() < 2) {
            missing.add((2 - openSpawns().size()) + " more spawn(s)");
        }
        return missing;
    }

    /** True if the map lists the mode and has everything it needs. */
    public boolean hosts(ArenaMode mode) {
        return modes.contains(mode) && missing(mode).isEmpty();
    }
}
