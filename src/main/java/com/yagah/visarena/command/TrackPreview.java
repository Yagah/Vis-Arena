package com.yagah.visarena.command;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import com.yagah.visarena.VisArena;
import com.yagah.visarena.map.ArenaMap;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * /arena map ... payload show: the track drawn in dust for a while, a dot every block along it, redrawn twice a
 * second. White along it, gold at checkpoints, green at the start, red at the end.
 */
@EventBusSubscriber(modid = VisArena.MODID)
final class TrackPreview {
    static final int SECONDS = 30;
    private static final double STEP = 1.0;
    private static final DustParticleOptions LINE = dust(0xFFFFFF, 0.8f);
    private static final DustParticleOptions CHECKPOINT = dust(0xFFAA00, 2.0f);
    private static final DustParticleOptions START = dust(0x55FF55, 2.0f);
    private static final DustParticleOptions END = dust(0xFF5555, 2.0f);

    private record Preview(ServerLevel level, List<ArenaMap.TrackPoint> track, long until) {
    }

    private static final List<Preview> PREVIEWS = new ArrayList<>();

    private TrackPreview() {
    }

    static void show(ServerLevel level, List<ArenaMap.TrackPoint> track) {
        PREVIEWS.add(new Preview(level, List.copyOf(track), level.getGameTime() + SECONDS * 20L));
    }

    private static DustParticleOptions dust(int rgb, float size) {
        return new DustParticleOptions(new Vector3f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f), size);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (PREVIEWS.isEmpty() || event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        PREVIEWS.removeIf(preview -> preview.level().getGameTime() >= preview.until());
        for (Preview preview : PREVIEWS) {
            List<ArenaMap.TrackPoint> track = preview.track();
            for (int i = 0; i < track.size(); i++) {
                ArenaMap.TrackPoint point = track.get(i);
                DustParticleOptions marker = i == 0 ? START : i == track.size() - 1 ? END : point.checkpoint() ? CHECKPOINT : LINE;
                Vec3 at = point.pos();
                preview.level().sendParticles(marker, at.x, at.y + 0.3, at.z, 6, 0.1, 0.3, 0.1, 0);
                if (i + 1 < track.size()) {
                    Vec3 to = track.get(i + 1).pos();
                    double length = at.distanceTo(to);
                    for (double d = STEP; d < length; d += STEP) {
                        Vec3 dot = at.lerp(to, d / length);
                        preview.level().sendParticles(LINE, dot.x, dot.y + 0.3, dot.z, 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }
}
