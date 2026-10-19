package com.yagah.visarena.map;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yagah.visarena.VisArena;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Every arena map and the lobby spawn, saved with the world (data/visarena.dat in the overworld). */
public final class ArenaData extends SavedData {
    /** The lobby: where players wait between matches. */
    public record Lobby(ResourceKey<Level> dimension, Spot spot) {
        public static final Codec<Lobby> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Lobby::dimension),
                Spot.CODEC.fieldOf("spot").forGetter(Lobby::spot)
        ).apply(instance, Lobby::new));
    }

    private record Stored(Optional<Lobby> lobby, List<ArenaMap> maps) {
        static final Codec<Stored> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Lobby.CODEC.optionalFieldOf("lobby").forGetter(Stored::lobby),
                ArenaMap.CODEC.listOf().fieldOf("maps").forGetter(Stored::maps)
        ).apply(instance, Stored::new));
    }

    private static final Factory<ArenaData> FACTORY = new Factory<>(ArenaData::new, ArenaData::load);

    private Optional<Lobby> lobby = Optional.empty();
    private final Map<String, ArenaMap> maps = new TreeMap<>();

    public static ArenaData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, VisArena.MODID);
    }

    private static ArenaData load(CompoundTag tag, HolderLookup.Provider registries) {
        ArenaData data = new ArenaData();
        Stored.CODEC.parse(NbtOps.INSTANCE, tag.get("arena"))
                .resultOrPartial(error -> VisArena.LOGGER.error("Could not load the arena maps: {}", error))
                .ifPresent(stored -> {
                    data.lobby = stored.lobby();
                    stored.maps().forEach(map -> data.maps.put(map.name(), map));
                });
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Stored.CODEC.encodeStart(NbtOps.INSTANCE, new Stored(lobby, List.copyOf(maps.values())))
                .resultOrPartial(error -> VisArena.LOGGER.error("Could not save the arena maps: {}", error))
                .ifPresent(encoded -> tag.put("arena", encoded));
        return tag;
    }

    public Optional<Lobby> lobby() {
        return lobby;
    }

    public void setLobby(Lobby lobby) {
        this.lobby = Optional.of(lobby);
        setDirty();
    }

    public Collection<ArenaMap> maps() {
        return maps.values();
    }

    /** Map names are case-insensitive, stored in lower case. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Optional<ArenaMap> map(String name) {
        return Optional.ofNullable(maps.get(key(name)));
    }

    /** False if a map by that name already exists. */
    public boolean add(ArenaMap map) {
        if (maps.putIfAbsent(map.name(), map) != null) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean remove(String name) {
        boolean removed = maps.remove(key(name)) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
