package com.yagah.visarena.map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** A place to stand and which way to face. */
public record Spot(Vec3 pos, float yaw, float pitch) {
    public static final Codec<Spot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Vec3.CODEC.fieldOf("pos").forGetter(Spot::pos),
            Codec.FLOAT.fieldOf("yaw").forGetter(Spot::yaw),
            Codec.FLOAT.fieldOf("pitch").forGetter(Spot::pitch)
    ).apply(instance, Spot::new));

    /** Where the player stands now, facing their way (pitch levelled, so arrivals look ahead). */
    public static Spot of(ServerPlayer player) {
        return new Spot(player.position(), player.getYRot(), 0);
    }
}
