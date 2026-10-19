package com.yagah.visarena.lobby;

import java.util.List;

import com.yagah.visarena.VisArena;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Arena menu item: a vanilla nether star marked with custom data (players have no Vis Arena items), kept in the
 * first hotbar slot of everyone in the lobby. Using it opens the mode menu.
 */
public final class MenuItem {
    private static final String TAG = VisArena.MODID + ":menu";

    private MenuItem() {
    }

    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.NETHER_STAR);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Arena")
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD).withBold(true)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right click to choose a game").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)))));
        return stack;
    }

    public static boolean is(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(TAG);
    }
}
