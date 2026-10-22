package com.yagah.visarena.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.yagah.vismod.vis.CharacterSwitch;
import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.map.ArenaData;
import com.yagah.visarena.match.Queues;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The menu the Arena item opens: one button per mode (click to join its queue, again to leave), and Close. Open menus
 * are redrawn every second and whenever a queue changes, so counts and countdowns stay current.
 */
public final class ModeMenu {
    private static final int ROWS = 3;
    /** Where each mode's button sits, in ArenaMode order. */
    private static final int[] MODE_SLOTS = {11, 12, 13, 14, 15};
    private static final int CLOSE_SLOT = 22;
    private static final int STATS_SLOT = 26;

    /** The open menu, so it can be redrawn. */
    private static final class Screen extends ButtonMenu {
        private final ServerPlayer player;

        Screen(int containerId, Inventory inventory, ServerPlayer player) {
            super(containerId, inventory, ButtonMenu.grid(ROWS), ROWS, slot -> click(player, slot));
            this.player = player;
            draw(player, getContainer());
        }
    }

    private ModeMenu() {
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new Screen(id, inventory, player), Component.literal("Choose a game")));
    }

    /** Redraws every open mode menu (changed slots reach the client with the menu's usual per-tick sync). */
    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof Screen screen) {
                draw(screen.player, screen.getContainer());
            }
        }
    }

    private static void draw(ServerPlayer player, Container grid) {
        ArenaData data = ArenaData.get(player.server);
        Optional<ArenaMode> queued = Queues.queuedFor(player.getUUID());
        ArenaMode[] modes = ArenaMode.values();
        for (int i = 0; i < modes.length; i++) {
            ArenaMode mode = modes[i];
            long maps = data.maps().stream().filter(map -> map.hosts(mode)).count();
            int min = ArenaConfig.minPlayers(mode);
            int max = ArenaConfig.maxPlayers(mode);
            boolean in = queued.equals(Optional.of(mode));
            List<Component> lore = new ArrayList<>(List.of(
                    Component.literal(mode.description()),
                    Component.empty(),
                    Component.literal("Players: " + (min == max ? String.valueOf(min) : min + "-" + max)),
                    Component.literal("Maps ready: " + maps).withStyle(maps > 0 ? ChatFormatting.GREEN : ChatFormatting.RED),
                    Component.literal("In queue: " + Queues.size(mode)),
                    Component.empty()));
            if (!mode.playable()) {
                lore.add(Component.literal("Coming soon").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                Queues.secondsLeft(mode).ifPresent(seconds -> lore.add(Component.literal("Starting in " + seconds + " s").withStyle(ChatFormatting.GREEN)));
                if (Queues.waitingForMap(player.server, mode)) {
                    lore.add(Component.literal("Waiting for a free map").withStyle(ChatFormatting.YELLOW));
                }
                lore.add(in ? Component.literal("You're queued - click to leave").withStyle(ChatFormatting.YELLOW)
                        : Component.literal("Click to join").withStyle(ChatFormatting.GREEN));
            }
            ItemStack button = ButtonMenu.button(mode.icon(),
                    Component.literal(mode.displayName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), lore);
            if (in) {
                button.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            grid.setItem(MODE_SLOTS[i], button);
        }
        grid.setItem(CLOSE_SLOT, ButtonMenu.button(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED), List.of()));
        grid.setItem(STATS_SLOT, ButtonMenu.button(Items.BOOK, Component.literal("Stats and leaderboard").withStyle(ChatFormatting.GOLD),
                List.of(Component.literal("Your record and the top players"))));
    }

    private static void click(ServerPlayer player, int slot) {
        if (slot == STATS_SLOT) {
            StatsMenu.open(player);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeContainer();
            return;
        }
        for (int i = 0; i < MODE_SLOTS.length; i++) {
            if (MODE_SLOTS[i] != slot) {
                continue;
            }
            ArenaMode mode = ArenaMode.values()[i];
            if (!mode.playable()) {
                player.displayClientMessage(Component.literal(mode.displayName() + " is coming soon").withStyle(ChatFormatting.YELLOW), true);
                return;
            }
            // Characters are locked once a match starts, so pick one first.
            if (!CharacterSwitch.hasCharacter(player) && Queues.queuedFor(player.getUUID()).isEmpty()) {
                player.displayClientMessage(Component.literal("Choose a Vis character first (press K)").withStyle(ChatFormatting.RED), true);
                return;
            }
            boolean joined = Queues.toggle(player, mode);
            player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.5f, joined ? 1.2f : 0.8f);
            player.displayClientMessage(Component.literal(joined ? "Queued for " + mode.displayName() : "Left the " + mode.displayName() + " queue")
                    .withStyle(joined ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
            return;
        }
    }
}
