package com.yagah.visarena.map;

import com.mojang.serialization.Codec;

import net.minecraft.ChatFormatting;
import net.minecraft.util.StringRepresentable;

/** The two sides in team modes. */
public enum TeamColor implements StringRepresentable {
    RED("red", ChatFormatting.RED),
    BLUE("blue", ChatFormatting.BLUE);

    public static final Codec<TeamColor> CODEC = StringRepresentable.fromEnum(TeamColor::values);

    private final String id;
    private final ChatFormatting color;

    TeamColor(String id, ChatFormatting color) {
        this.id = id;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public ChatFormatting color() {
        return color;
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
