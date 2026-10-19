package com.yagah.visarena;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Minigames on top of Vis Mod. Server side only: it registers nothing a client must know (no items, blocks, entities,
 * payloads or synced config), and draws everything with vanilla screens, scoreboards, boss bars and titles.
 */
@Mod(VisArena.MODID)
public class VisArena {
    public static final String MODID = "visarena";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VisArena(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ArenaConfig.SPEC);
    }
}
