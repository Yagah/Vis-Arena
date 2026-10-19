package com.yagah.visarena.lobby;

import java.util.List;

import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.map.ArenaData;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Items;

/** The menu the Arena item opens: one button per mode, and Close. */
public final class ModeMenu {
    private static final int ROWS = 3;
    /** Where each mode's button sits, in ArenaMode order. */
    private static final int[] MODE_SLOTS = {10, 12, 14, 16};
    private static final int CLOSE_SLOT = 22;

    private ModeMenu() {
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ButtonMenu(id, inventory, buttons(player), ROWS,
                slot -> click(player, slot)), Component.literal("Choose a game")));
    }

    private static SimpleContainer buttons(ServerPlayer player) {
        SimpleContainer grid = ButtonMenu.grid(ROWS);
        ArenaData data = ArenaData.get(player.server);
        ArenaMode[] modes = ArenaMode.values();
        for (int i = 0; i < modes.length; i++) {
            ArenaMode mode = modes[i];
            long maps = data.maps().stream().filter(map -> map.hosts(mode)).count();
            int min = ArenaConfig.minPlayers(mode);
            int max = ArenaConfig.maxPlayers(mode);
            grid.setItem(MODE_SLOTS[i], ButtonMenu.button(mode.icon(),
                    Component.literal(mode.displayName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    List.of(
                            Component.literal(mode.description()),
                            Component.empty(),
                            Component.literal("Players: " + (min == max ? String.valueOf(min) : min + "-" + max)),
                            Component.literal("Maps ready: " + maps).withStyle(maps > 0 ? ChatFormatting.GREEN : ChatFormatting.RED),
                            Component.empty(),
                            Component.literal("Queues open soon").withStyle(ChatFormatting.DARK_GRAY))));
        }
        grid.setItem(CLOSE_SLOT, ButtonMenu.button(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED), List.of()));
        return grid;
    }

    private static void click(ServerPlayer player, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeContainer();
            return;
        }
        for (int i = 0; i < MODE_SLOTS.length; i++) {
            if (MODE_SLOTS[i] == slot) {
                ArenaMode mode = ArenaMode.values()[i];
                player.level().playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5f, 1.0f);
                player.displayClientMessage(Component.literal("Queueing for " + mode.displayName() + " isn't open yet")
                        .withStyle(ChatFormatting.YELLOW), true);
                return;
            }
        }
    }
}
