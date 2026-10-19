package com.yagah.visarena.mode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.yagah.visarena.map.Spot;
import com.yagah.visarena.match.Match;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * What makes a mode: where players start and respawn, when someone has won, and what the sidebar shows. The match
 * engine ({@link Match}) runs everything else the same for every mode.
 */
public interface ModeRules {
    /** How a match ended: the winning player (or team, later), or none for a draw, and the headline shown to everyone. */
    record Result(Optional<UUID> winner, Component headline, Component detail) {
    }

    /** Each fighter's first spawn. */
    Map<UUID, Spot> startSpots(Match match);

    /** Where a fighter comes back after dying. */
    Spot respawnSpot(Match match, ServerPlayer player);

    /** A result once the match is won outright (score limit, or everyone else gone), else empty. Checked every tick. */
    Optional<Result> decided(Match match);

    /**
     * When time runs out: the result, or empty for sudden death on a tie. With {@code last} (sudden death is over too),
     * always a result, a draw if still tied.
     */
    Optional<Result> timeUp(Match match, boolean last);

    /** The sidebar lines under the timer: the scores. */
    List<Component> scoreLines(Match match);
}
