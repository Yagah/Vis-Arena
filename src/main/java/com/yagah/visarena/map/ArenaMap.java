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
import net.minecraft.world.phys.Vec3;

/**
 * An arena: a place built by hand in one dimension, marked with /arena map commands. It lists the modes it hosts, its
 * spawns, the height below which a player has fallen out, and Core Siege's core zone.
 */
public final class ArenaMap {
    /**
     * A spawn point. Plain ones (and team ones) serve FFA and 1v1; one with a team serves only that team in TDM; one
     * with a siege side serves only that side in Core Siege and Payload. In Payload, its stage (0 at the start, then one
     * more past each checkpoint) says when it's used.
     */
    public record Spawn(Spot spot, Optional<TeamColor> team, Optional<SiegeSide> side, int stage) {
        public static final Codec<Spawn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Spot.CODEC.fieldOf("spot").forGetter(Spawn::spot),
                TeamColor.CODEC.optionalFieldOf("team").forGetter(Spawn::team),
                SiegeSide.CODEC.optionalFieldOf("side").forGetter(Spawn::side),
                Codec.INT.optionalFieldOf("stage", 0).forGetter(Spawn::stage)
        ).apply(instance, Spawn::new));
    }

    /** A point on the Payload track; the track runs straight from one to the next. A checkpoint locks progress there. */
    public record TrackPoint(Vec3 pos, boolean checkpoint) {
        public static final Codec<TrackPoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("pos").forGetter(TrackPoint::pos),
                Codec.BOOL.optionalFieldOf("checkpoint", false).forGetter(TrackPoint::checkpoint)
        ).apply(instance, TrackPoint::new));
    }

    private static final Codec<ArenaMode> MODE_CODEC = Codec.STRING.xmap(
            id -> ArenaMode.byId(id).orElseThrow(), ArenaMode::id);

    public static final Codec<ArenaMap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(map -> map.name),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(map -> map.dimension),
            MODE_CODEC.listOf().fieldOf("modes").forGetter(map -> List.copyOf(map.modes)),
            Spawn.CODEC.listOf().fieldOf("spawns").forGetter(map -> map.spawns),
            Codec.INT.optionalFieldOf("floor").forGetter(map -> map.floor),
            BoundingBox.CODEC.optionalFieldOf("core").forGetter(map -> map.core),
            TrackPoint.CODEC.listOf().optionalFieldOf("track", List.of()).forGetter(map -> map.track)
    ).apply(instance, ArenaMap::new));

    private final String name;
    private final ResourceKey<Level> dimension;
    private final Set<ArenaMode> modes = EnumSet.noneOf(ArenaMode.class);
    private final List<Spawn> spawns;
    private Optional<Integer> floor;
    private Optional<BoundingBox> core;
    private final List<TrackPoint> track;

    public ArenaMap(String name, ResourceKey<Level> dimension) {
        this(name, dimension, List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
    }

    private ArenaMap(String name, ResourceKey<Level> dimension, List<ArenaMode> modes, List<Spawn> spawns,
            Optional<Integer> floor, Optional<BoundingBox> core, List<TrackPoint> track) {
        this.track = new ArrayList<>(track);
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

    /** A side's spawns for a Payload stage: the latest stage at or before it that has any (so stages may be skipped). */
    public List<Spawn> stageSpawns(SiegeSide side, int stage) {
        for (int s = stage; s >= 0; s--) {
            int wanted = s;
            List<Spawn> found = siegeSpawns(side).stream().filter(spawn -> spawn.stage() == wanted).toList();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return siegeSpawns(side);
    }

    /** The Payload track, start to end. */
    public List<TrackPoint> track() {
        return track;
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
        if (mode.track()) {
            if (track.size() < 2) {
                missing.add("a payload track (start and end, at least)");
            }
            for (SiegeSide side : SiegeSide.values()) {
                if (stageSpawns(side, 0).stream().noneMatch(spawn -> spawn.stage() == 0)) {
                    missing.add("an " + side.id() + " spawn for stage 0");
                }
            }
        } else if (mode.cores()) {
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
