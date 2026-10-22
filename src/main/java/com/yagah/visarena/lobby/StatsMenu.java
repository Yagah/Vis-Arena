package com.yagah.visarena.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.yagah.visarena.mode.ArenaMode;
import com.yagah.visarena.stats.ArenaStats;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Stats and leaderboard, opened from the mode menu: your record and the top players, overall and per mode. */
public final class StatsMenu {
    private static final int ROWS = 3;
    private static final int OVERALL_SLOT = 4;
    /** Where each mode sits, in ArenaMode order (as in the mode menu). */
    private static final int[] MODE_SLOTS = {11, 12, 13, 14, 15};
    private static final int BACK_SLOT = 22;
    private static final int TOP = 5;

    private StatsMenu() {
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ButtonMenu(id, inventory, buttons(player), ROWS,
                slot -> {
                    if (slot == BACK_SLOT) {
                        ModeMenu.open(player);
                    }
                }), Component.literal("Stats and leaderboard")));
    }

    private static SimpleContainer buttons(ServerPlayer player) {
        SimpleContainer grid = ButtonMenu.grid(ROWS);
        ArenaStats stats = ArenaStats.get(player.server);
        grid.setItem(OVERALL_SLOT, entry(stats, player, Optional.empty(), Items.EXPERIENCE_BOTTLE, "All modes"));
        ArenaMode[] modes = ArenaMode.values();
        for (int i = 0; i < modes.length; i++) {
            grid.setItem(MODE_SLOTS[i], entry(stats, player, Optional.of(modes[i]), modes[i].icon(), modes[i].displayName()));
        }
        grid.setItem(BACK_SLOT, ButtonMenu.button(Items.ARROW, Component.literal("Back").withStyle(ChatFormatting.YELLOW), List.of()));
        return grid;
    }

    private static ItemStack entry(ArenaStats stats, ServerPlayer player, Optional<ArenaMode> mode, Item icon, String title) {
        ArenaStats.Record mine = stats.player(player.getUUID()).map(p -> p.record(mode)).orElse(ArenaStats.Record.NONE);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("You: " + mine.wins() + " wins of " + mine.played() + " games").withStyle(ChatFormatting.WHITE));
        lore.add(Component.literal(mine.kills() + " kills, " + mine.deaths() + " deaths"));
        lore.add(Component.empty());
        lore.add(Component.literal("Most wins").withStyle(ChatFormatting.GOLD));
        List<ArenaStats.Player> top = stats.top(mode, TOP);
        if (top.isEmpty()) {
            lore.add(Component.literal("No games yet"));
        }
        for (int i = 0; i < top.size(); i++) {
            ArenaStats.Record record = top.get(i).record(mode);
            lore.add(Component.literal((i + 1) + ". " + top.get(i).name() + "  ").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(record.wins() + " wins, " + record.kills() + " kills").withStyle(ChatFormatting.GRAY)));
        }
        return ButtonMenu.button(icon, Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), lore);
    }
}
