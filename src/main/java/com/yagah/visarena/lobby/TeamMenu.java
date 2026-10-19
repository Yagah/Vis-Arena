package com.yagah.visarena.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.yagah.visarena.map.TeamColor;
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
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * The team screen, open to everyone queued for a team mode while its countdown runs: Red and Blue buttons on top, and
 * below them three columns of player heads (red, undecided, blue), redrawn live. Clicking a side joins it, clicking it
 * again goes back to undecided; a side can't take more than half the players. Undecided players are placed when the
 * match starts (see TeamDeal).
 */
public final class TeamMenu {
    private static final int ROWS = 6;
    private static final int RED_SLOT = 1;
    private static final int STATUS_SLOT = 4;
    private static final int BLUE_SLOT = 7;
    private static final int BACK_SLOT = 48;
    private static final int LEAVE_SLOT = 50;
    /** Where each column of heads starts (red, undecided, blue), three wide, from the second row. */
    private static final int RED_COLUMN = 0;
    private static final int UNDECIDED_COLUMN = 3;
    private static final int BLUE_COLUMN = 6;
    private static final int HEAD_ROWS = 4;

    private static final class Screen extends ButtonMenu {
        private final ServerPlayer player;

        Screen(int containerId, Inventory inventory, ServerPlayer player) {
            super(containerId, inventory, ButtonMenu.grid(ROWS), ROWS, slot -> click(player, slot));
            this.player = player;
            draw(player, getContainer());
        }
    }

    private TeamMenu() {
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new Screen(id, inventory, player), Component.literal("Pick your team")));
    }

    /** Redraws every open team screen, and closes those whose countdown is over (the match started, or it stopped). */
    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof Screen screen) {
                if (Queues.picking(player.getUUID())) {
                    draw(screen.player, screen.getContainer());
                } else {
                    player.closeContainer();
                }
            }
        }
    }

    private static void draw(ServerPlayer viewer, Container grid) {
        Optional<ArenaMode> queued = Queues.queuedFor(viewer.getUUID()).filter(ArenaMode::teams);
        for (int i = 0; i < grid.getContainerSize(); i++) {
            grid.setItem(i, ButtonMenu.button(Items.GRAY_STAINED_GLASS_PANE, Component.literal(" "), List.of()));
        }
        if (queued.isEmpty()) {
            return;
        }
        ArenaMode mode = queued.get();
        int cap = Queues.sideCap(mode);
        Optional<TeamColor> mine = Queues.pick(viewer.getUUID());
        grid.setItem(RED_SLOT, sideButton(TeamColor.RED, mode, cap, mine));
        grid.setItem(BLUE_SLOT, sideButton(TeamColor.BLUE, mode, cap, mine));
        int seconds = Queues.secondsLeft(mode).orElse(0);
        grid.setItem(STATUS_SLOT, ButtonMenu.button(Items.CLOCK, Component.literal(mode.displayName() + " starts in " + seconds + " s")
                .withStyle(ChatFormatting.GOLD), List.of(
                        Component.literal("Click a team to join it, again to undecide."),
                        Component.literal("Undecided players are placed to even out"),
                        Component.literal("the teams (by skill) when it starts."))));
        List<UUID> red = new ArrayList<>();
        List<UUID> blue = new ArrayList<>();
        List<UUID> undecided = new ArrayList<>();
        for (UUID id : Queues.nextPlayers(mode)) {
            Optional<TeamColor> pick = Queues.pick(id);
            (pick.isEmpty() ? undecided : pick.get() == TeamColor.RED ? red : blue).add(id);
        }
        heads(viewer, grid, red, RED_COLUMN, Optional.of(TeamColor.RED));
        heads(viewer, grid, undecided, UNDECIDED_COLUMN, Optional.empty());
        heads(viewer, grid, blue, BLUE_COLUMN, Optional.of(TeamColor.BLUE));
        grid.setItem(BACK_SLOT, ButtonMenu.button(Items.ARROW, Component.literal("Games").withStyle(ChatFormatting.YELLOW),
                List.of(Component.literal("Back to the game list (you stay queued)"))));
        grid.setItem(LEAVE_SLOT, ButtonMenu.button(Items.BARRIER, Component.literal("Leave the queue").withStyle(ChatFormatting.RED), List.of()));
    }

    private static ItemStack sideButton(TeamColor team, ArenaMode mode, int cap, Optional<TeamColor> mine) {
        long count = Queues.picked(mode, team);
        boolean in = mine.equals(Optional.of(team));
        ItemStack button = ButtonMenu.button(team == TeamColor.RED ? Items.RED_WOOL : Items.BLUE_WOOL,
                Component.literal(team.displayName() + " team").withStyle(team.color(), ChatFormatting.BOLD),
                List.of(Component.literal(count + " of " + cap + " places taken"),
                        in ? Component.literal("You're on " + team.displayName() + " - click to undecide").withStyle(ChatFormatting.YELLOW)
                                : count >= cap ? Component.literal("Full").withStyle(ChatFormatting.RED)
                                : Component.literal("Click to join").withStyle(ChatFormatting.GREEN)));
        if (in) {
            button.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return button;
    }

    /** A column of player heads, three wide, from the second row down. */
    private static void heads(ServerPlayer viewer, Container grid, List<UUID> players, int column, Optional<TeamColor> team) {
        for (int i = 0; i < players.size() && i < HEAD_ROWS * 3; i++) {
            ServerPlayer player = viewer.server.getPlayerList().getPlayer(players.get(i));
            if (player == null) {
                continue;
            }
            ItemStack head = ButtonMenu.button(Items.PLAYER_HEAD,
                    Component.literal(player.getGameProfile().getName() + (player == viewer ? " (you)" : ""))
                            .withStyle(team.map(TeamColor::color).orElse(ChatFormatting.GRAY)),
                    List.of(Component.literal(team.map(t -> t.displayName() + " team").orElse("Undecided"))));
            head.set(DataComponents.PROFILE, new ResolvableProfile(player.getGameProfile()));
            grid.setItem((1 + i / 3) * 9 + column + i % 3, head);
        }
    }

    private static void click(ServerPlayer player, int slot) {
        switch (slot) {
            case RED_SLOT, BLUE_SLOT -> {
                TeamColor team = slot == RED_SLOT ? TeamColor.RED : TeamColor.BLUE;
                Queues.choose(player, team).ifPresentOrElse(
                        refusal -> player.displayClientMessage(Component.literal(refusal).withStyle(ChatFormatting.RED), true),
                        () -> player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.5f, 1.2f));
            }
            case BACK_SLOT -> ModeMenu.open(player);
            case LEAVE_SLOT -> {
                Queues.queuedFor(player.getUUID()).ifPresent(mode -> Queues.toggle(player, mode));
                player.closeContainer();
                player.displayClientMessage(Component.literal("Left the queue").withStyle(ChatFormatting.YELLOW), true);
            }
            default -> {
            }
        }
    }
}
