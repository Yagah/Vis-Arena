package com.yagah.visarena;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Nobody on the server gets hungry ({@link ArenaConfig#NO_HUNGER}): every player's hunger bar stays full and nothing they
 * do wears it down. A full bar heals nothing while the naturalRegeneration gamerule is off, which is how the server keeps
 * health from coming back on its own; saturation is kept at zero so it can't heal either way.
 */
@EventBusSubscriber(modid = VisArena.MODID)
final class NoHunger {
    private static final int FULL = 20;

    private NoHunger() {
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ArenaConfig.NO_HUNGER.get()) {
            return;
        }
        FoodData food = player.getFoodData();
        food.setExhaustion(0);
        if (food.getFoodLevel() != FULL) {
            food.setFoodLevel(FULL);
        }
        if (food.getSaturationLevel() != 0) {
            food.setSaturation(0);
        }
    }
}
