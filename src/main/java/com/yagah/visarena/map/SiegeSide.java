package com.yagah.visarena.map;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/** Core Siege's two sides each half: whoever attacks the core and whoever defends it. Spawns are marked with one. */
public enum SiegeSide implements StringRepresentable {
    ATTACK("attack"),
    DEFEND("defend");

    public static final Codec<SiegeSide> CODEC = StringRepresentable.fromEnum(SiegeSide::values);

    private final String id;

    SiegeSide(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
