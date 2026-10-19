package com.yagah.visarena.map;

import com.mojang.serialization.Codec;

import net.minecraft.ChatFormatting;
import net.minecraft.util.StringRepresentable;

/** The two sides in team modes. */
public enum TeamColor implements StringRepresentable {
    RED("red", "Red", ChatFormatting.RED),
    BLUE("blue", "Blue", ChatFormatting.BLUE);

    public static final Codec<TeamColor> CODEC = StringRepresentable.fromEnum(TeamColor::values);

    private final String id;
    private final String displayName;
    private final ChatFormatting color;

    TeamColor(String id, String displayName, ChatFormatting color) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public ChatFormatting color() {
        return color;
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
