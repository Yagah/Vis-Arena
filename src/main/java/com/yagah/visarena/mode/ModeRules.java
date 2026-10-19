package com.yagah.visarena.mode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /** How a match ended: the winning players (one, or a whole team; none for a draw), and what everyone is shown. */
    record Result(Set<UUID> winners, Component headline, Component detail) {
    }

    /** A finished round: who took it (empty for a drawn round) and the headline shown. */
    record RoundEnd(Optional<UUID> winner, Component headline) {
    }

    /**
     * True for modes played in rounds (1v1): a downed fighter is out, watching as a spectator, until the next round,
     * instead of respawning. {@link #roundOver} says when a round ends; {@link #decided} then says if the match has.
     */
    default boolean rounds() {
        return false;
    }

    /** For round modes: the round's result once it's over, else empty. When its time is up, always a result. */
    default Optional<RoundEnd> roundOver(Match match, boolean timeUp) {
        return Optional.empty();
    }

    /** Each fighter's first spawn (and, in round modes, their spawn each round). */
    Map<UUID, Spot> startSpots(Match match);

    /** Where a fighter comes back after dying. */
    Spot respawnSpot(Match match, ServerPlayer player);

    /**
     * A result once the match is won outright (score limit, or everyone else gone), else empty. Asked after each kill
     * or departure.
     */
    Optional<Result> decided(Match match);

    /**
     * When time runs out: the result, or empty for sudden death on a tie. With {@code last} (sudden death is over too),
     * always a result, a draw if still tied.
     */
    Optional<Result> timeUp(Match match, boolean last);

    /** The sidebar lines under the timer: the scores. */
    List<Component> scoreLines(Match match);
}
