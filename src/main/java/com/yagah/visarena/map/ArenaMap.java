package com.yagah.visarena.map;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
 * spawns (free, or for one team), the height below which a player has fallen out, and Core Siege's core zones.
 */
public final class ArenaMap {
    /** A spawn point; with a team, only that team spawns there. */
    public record Spawn(Spot spot, Optional<TeamColor> team) {
        public static final Codec<Spawn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Spot.CODEC.fieldOf("spot").forGetter(Spawn::spot),
                TeamColor.CODEC.optionalFieldOf("team").forGetter(Spawn::team)
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
            Codec.unboundedMap(TeamColor.CODEC, BoundingBox.CODEC).fieldOf("cores").forGetter(map -> map.cores)
    ).apply(instance, ArenaMap::new));

    private final String name;
    private final ResourceKey<Level> dimension;
    private final Set<ArenaMode> modes = EnumSet.noneOf(ArenaMode.class);
    private final List<Spawn> spawns;
    private Optional<Integer> floor;
    private final Map<TeamColor, BoundingBox> cores = new EnumMap<>(TeamColor.class);

    public ArenaMap(String name, ResourceKey<Level> dimension) {
        this(name, dimension, List.of(), List.of(), Optional.empty(), Map.of());
    }

    private ArenaMap(String name, ResourceKey<Level> dimension, List<ArenaMode> modes, List<Spawn> spawns,
            Optional<Integer> floor, Map<TeamColor, BoundingBox> cores) {
        this.name = name;
        this.dimension = dimension;
        this.modes.addAll(modes);
        this.spawns = new ArrayList<>(spawns);
        this.floor = floor;
        this.cores.putAll(cores);
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

    public Optional<Integer> floor() {
        return floor;
    }

    public void setFloor(Optional<Integer> floor) {
        this.floor = floor;
    }

    public Map<TeamColor, BoundingBox> cores() {
        return cores;
    }

    /** What this map still lacks to host the mode; empty once it is ready. */
    public List<String> missing(ArenaMode mode) {
        List<String> missing = new ArrayList<>();
        if (mode.teams()) {
            for (TeamColor team : TeamColor.values()) {
                if (spawns.stream().noneMatch(spawn -> spawn.team().equals(Optional.of(team)))) {
                    missing.add("a " + team.id() + " spawn");
                }
            }
        } else if (spawns.size() < 2) {
            missing.add((2 - spawns.size()) + " more spawn(s)");
        }
        if (mode.cores()) {
            for (TeamColor team : TeamColor.values()) {
                if (!cores.containsKey(team)) {
                    missing.add("a " + team.id() + " core");
                }
            }
        }
        return missing;
    }

    /** True if the map lists the mode and has everything it needs. */
    public boolean hosts(ArenaMode mode) {
        return modes.contains(mode) && missing(mode).isEmpty();
    }
}
