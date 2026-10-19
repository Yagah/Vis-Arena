package com.yagah.visarena.lobby;

import com.yagah.vismod.team.VisRound;
import com.yagah.visarena.VisArena;
import com.yagah.visarena.map.ArenaData;
import com.yagah.visarena.match.Matches;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The lobby, where players wait between matches. Everyone not building is managed here: sent to the lobby spawn on
 * joining and on respawning, kept in adventure mode, and given the Arena menu item in the first hotbar slot, which
 * comes back if moved, dropped or lost.
 * <p>
 * Builders ({@code /arena build}) are left alone, in creative, to build and mark maps.
 */
@EventBusSubscriber(modid = VisArena.MODID)
public final class Lobby {
    private static final String BUILDER = VisArena.MODID + ":builder";
    /** How often the menu item and game mode are put right. */
    private static final int CHECK_TICKS = 10;
    private static final int MENU_SLOT = 0;

    private Lobby() {
    }

    /** True if the lobby manages this player: everyone but builders and players in a match. */
    public static boolean managed(ServerPlayer player) {
        return !builder(player) && !Matches.inMatch(player);
    }

    public static boolean builder(ServerPlayer player) {
        return player.getPersistentData().getBoolean(BUILDER);
    }

    /** Builder mode on: creative, no menu item, left alone. Off: back to the lobby. */
    public static void setBuilder(ServerPlayer player, boolean builder) {
        player.getPersistentData().putBoolean(BUILDER, builder);
        if (builder) {
            removeMenuItems(player.getInventory());
            player.setGameMode(GameType.CREATIVE);
        } else {
            send(player);
        }
    }

    /**
     * Into the lobby, fresh: at the lobby spawn (if one is set), in adventure mode, healed and fed, with an empty
     * inventory but for the menu item, and a Vis round reset.
     */
    public static void send(ServerPlayer player) {
        ArenaData.get(player.server).lobby().ifPresent(lobby -> {
            ServerLevel level = player.server.getLevel(lobby.dimension());
            if (level != null) {
                player.teleportTo(level, lobby.spot().pos().x, lobby.spot().pos().y, lobby.spot().pos().z, lobby.spot().yaw(), lobby.spot().pitch());
            }
        });
        player.setGameMode(GameType.ADVENTURE);
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5);
        player.getInventory().clearContent();
        player.getInventory().setItem(MENU_SLOT, MenuItem.create());
        player.containerMenu.broadcastChanges();
        VisRound.reset(player);
    }

    /** The menu item in the first slot and nowhere else, and adventure mode. */
    private static void keepInPlace(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        if (!MenuItem.is(inventory.getItem(MENU_SLOT)) || countMenuItems(inventory) != 1) {
            removeMenuItems(inventory);
            ItemStack displaced = inventory.getItem(MENU_SLOT);
            inventory.setItem(MENU_SLOT, MenuItem.create());
            if (!displaced.isEmpty() && !inventory.add(displaced)) {
                player.drop(displaced, false);
            }
            player.containerMenu.broadcastChanges();
        }
        if (player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
            player.setGameMode(GameType.ADVENTURE);
        }
    }

    private static int countMenuItems(Inventory inventory) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (MenuItem.is(inventory.getItem(i))) {
                count++;
            }
        }
        return count;
    }

    private static void removeMenuItems(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (MenuItem.is(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && managed(player)) {
            send(player);
        }
    }

    /** Persistent data isn't carried over to the new player entity on death; the builder flag is. */
    @SubscribeEvent
    static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer original && builder(original)) {
            event.getEntity().getPersistentData().putBoolean(BUILDER, true);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && managed(player)) {
            send(player);
        }
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % CHECK_TICKS == 0 && managed(player)) {
            keepInPlace(player);
        }
    }

    /** A thrown menu item is destroyed rather than dropped, and put back at once. */
    @SubscribeEvent
    static void onToss(ItemTossEvent event) {
        if (MenuItem.is(event.getEntity().getItem()) && event.getPlayer() instanceof ServerPlayer player) {
            event.setCanceled(true);
            keepInPlace(player);
        }
    }

    /** Nobody in the lobby can be hurt, except by the void or /kill (which send them back to the lobby spawn). */
    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && managed(player)
                && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    /** Nothing drops on death: the arena world has no loot. */
    @SubscribeEvent
    static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && managed(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && MenuItem.is(event.getItemStack())) {
            ModeMenu.open(player);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Right clicking a block with the item in hand opens the menu too. */
    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && MenuItem.is(event.getItemStack())) {
            ModeMenu.open(player);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
