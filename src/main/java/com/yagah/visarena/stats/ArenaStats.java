package com.yagah.visarena.stats;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yagah.visarena.VisArena;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Each player's arena record per mode (games, wins, kills, deaths), saved with the world (data/visarena_stats.dat). */
public final class ArenaStats extends SavedData {
    /** One player's record in one mode (or, summed, all modes). */
    public record Record(int played, int wins, int kills, int deaths) {
        public static final Record NONE = new Record(0, 0, 0, 0);
        static final Codec<Record> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("played").forGetter(Record::played),
                Codec.INT.fieldOf("wins").forGetter(Record::wins),
                Codec.INT.fieldOf("kills").forGetter(Record::kills),
                Codec.INT.fieldOf("deaths").forGetter(Record::deaths)
        ).apply(instance, Record::new));

        Record plus(Record other) {
            return new Record(played + other.played, wins + other.wins, kills + other.kills, deaths + other.deaths);
        }
    }

    /** A player's name (as last seen) and their record per mode. */
    public record Player(String name, Map<ArenaMode, Record> modes) {
        private static final Codec<Map<ArenaMode, Record>> MODES_CODEC = Codec.unboundedMap(Codec.STRING, Record.CODEC).xmap(
                byId -> {
                    Map<ArenaMode, Record> modes = new EnumMap<>(ArenaMode.class);
                    byId.forEach((id, record) -> ArenaMode.byId(id).ifPresent(mode -> modes.put(mode, record)));
                    return modes;
                },
                modes -> {
                    Map<String, Record> byId = new HashMap<>();
                    modes.forEach((mode, record) -> byId.put(mode.id(), record));
                    return byId;
                });
        static final Codec<Player> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(Player::name),
                MODES_CODEC.fieldOf("modes").forGetter(Player::modes)
        ).apply(instance, Player::new));

        /** Their record in one mode, or across all of them for an empty mode. */
        public Record record(Optional<ArenaMode> mode) {
            return mode.map(m -> modes.getOrDefault(m, Record.NONE))
                    .orElseGet(() -> modes.values().stream().reduce(Record.NONE, Record::plus));
        }
    }

    private static final Codec<Map<UUID, Player>> CODEC = Codec.unboundedMap(Codec.STRING.xmap(UUID::fromString, UUID::toString), Player.CODEC);
    private static final Factory<ArenaStats> FACTORY = new Factory<>(ArenaStats::new, ArenaStats::load);

    private final Map<UUID, Player> players = new HashMap<>();

    public static ArenaStats get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, VisArena.MODID + "_stats");
    }

    private static ArenaStats load(CompoundTag tag, HolderLookup.Provider registries) {
        ArenaStats stats = new ArenaStats();
        CODEC.parse(NbtOps.INSTANCE, tag.get("players"))
                .resultOrPartial(error -> VisArena.LOGGER.error("Could not load the arena stats: {}", error))
                .ifPresent(stats.players::putAll);
        return stats;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, players)
                .resultOrPartial(error -> VisArena.LOGGER.error("Could not save the arena stats: {}", error))
                .ifPresent(encoded -> tag.put("players", encoded));
        return tag;
    }

    /** Adds one finished match to a player's record. */
    public void add(UUID id, String name, ArenaMode mode, boolean won, int kills, int deaths) {
        Player old = players.getOrDefault(id, new Player(name, Map.of()));
        Map<ArenaMode, Record> modes = new EnumMap<>(ArenaMode.class);
        modes.putAll(old.modes());
        modes.merge(mode, new Record(1, won ? 1 : 0, kills, deaths), Record::plus);
        players.put(id, new Player(name, modes));
        setDirty();
    }

    public Optional<Player> player(UUID id) {
        return Optional.ofNullable(players.get(id));
    }

    /** The players with the most wins in a mode (or all modes), then the most kills, who have played it. */
    public List<Player> top(Optional<ArenaMode> mode, int count) {
        return players.values().stream()
                .filter(player -> player.record(mode).played() > 0)
                .sorted(Comparator.comparingInt((Player player) -> player.record(mode).wins())
                        .thenComparingInt(player -> player.record(mode).kills()).reversed())
                .limit(count)
                .toList();
    }
}
