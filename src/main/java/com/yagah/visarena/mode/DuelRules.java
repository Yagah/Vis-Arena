package com.yagah.visarena.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.map.Spot;
import com.yagah.visarena.match.Fighter;
import com.yagah.visarena.match.Match;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 1v1: rounds. Whoever is left standing takes the round. A round that runs out of time with nobody downed (or with both
 * down) is drawn, whatever their health. First to the score limit in rounds wins the match; "drawsToEnd" (3) drawn rounds
 * end it as no contest: everyone back to the lobby, nothing recorded.
 */
public final class DuelRules implements ModeRules {
    /** Drawn rounds so far this match. */
    private int draws;

    @Override
    public boolean rounds() {
        return true;
    }

    /** A downed fighter sits out the rest of the round. */
    @Override
    public OptionalInt respawnDelay(Match match, Fighter fighter) {
        return OptionalInt.empty();
    }

    /** The spawns, shuffled, one each (the two farthest apart if there are more). */
    @Override
    public Map<UUID, Spot> startSpots(Match match) {
        List<ArenaMap.Spawn> spawns = new ArrayList<>(match.map().openSpawns());
        Collections.shuffle(spawns);
        List<Spot> picked = farthestPair(spawns);
        Map<UUID, Spot> spots = new HashMap<>();
        int i = 0;
        for (UUID id : match.fighters().keySet()) {
            spots.put(id, picked.get(i++ % picked.size()));
        }
        return spots;
    }

    /** Only used if someone is put back mid-countdown; the spawn farthest from the opponent. */
    @Override
    public Spot respawnSpot(Match match, ServerPlayer player) {
        List<ServerPlayer> others = match.online().stream().filter(other -> other != player).toList();
        return match.map().openSpawns().stream()
                .max(Comparator.comparingDouble(spawn -> others.stream()
                        .mapToDouble(other -> other.position().distanceToSqr(spawn.spot().pos()))
                        .min().orElse(0)))
                .orElseThrow()
                .spot();
    }

    @Override
    public Optional<RoundEnd> roundOver(Match match, boolean timeUp) {
        List<Fighter> present = match.fighters().values().stream().filter(Fighter::present).toList();
        List<Fighter> standing = present.stream().filter(fighter -> !fighter.out()).toList();
        if (standing.size() == 1 && present.size() > 1) {
            return Optional.of(takes(standing.getFirst()));
        }
        if (standing.isEmpty()) {
            return Optional.of(drawn("Both went down"));
        }
        // Time's up with nobody downed: a draw, whoever has more health.
        return timeUp ? Optional.of(drawn("Time's up")) : Optional.empty();
    }

    @Override
    public Optional<Result> decided(Match match) {
        List<Fighter> present = match.fighters().values().stream().filter(Fighter::present).toList();
        if (present.size() == 1 && match.fighters().size() > 1) {
            return Optional.of(win(match, present.getFirst(), "Their opponent left"));
        }
        int limit = ArenaConfig.scoreLimit(ArenaMode.DUEL);
        Optional<Fighter> reached = present.stream().filter(fighter -> fighter.roundsWon() >= limit).findFirst();
        if (reached.isPresent()) {
            return Optional.of(win(match, reached.get(), score(match)));
        }
        if (draws >= ArenaConfig.DUEL_DRAWS_TO_END.get()) {
            return timeUp(match, true);
        }
        return Optional.empty();
    }

    /** Only reached on too many drawn rounds: no contest, back to the lobby with nothing recorded. */
    @Override
    public Optional<Result> timeUp(Match match, boolean last) {
        return Optional.of(new Result(Set.of(), Component.literal("No contest").withStyle(ChatFormatting.YELLOW),
                Component.literal(draws + " drawn rounds: nothing recorded"), false));
    }

    @Override
    public List<Component> scoreLines(Match match) {
        List<Component> lines = new ArrayList<>();
        for (Fighter fighter : match.fighters().values()) {
            lines.add((fighter.present() ? Match.name(fighter) : Match.name(fighter).withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(" " + fighter.roundsWon()).withStyle(ChatFormatting.GOLD)));
        }
        lines.add(Component.empty());
        lines.add(Component.literal("First to " + ArenaConfig.scoreLimit(ArenaMode.DUEL) + " rounds").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Draws " + draws + " of " + ArenaConfig.DUEL_DRAWS_TO_END.get()).withStyle(ChatFormatting.GRAY));
        return lines;
    }

    /** Rounds as "3 - 1", in fighter order. */
    private static String score(Match match) {
        return String.join(" - ", match.fighters().values().stream().map(fighter -> String.valueOf(fighter.roundsWon())).toList());
    }

    private static List<Spot> farthestPair(List<ArenaMap.Spawn> spawns) {
        if (spawns.size() <= 2) {
            return spawns.stream().map(ArenaMap.Spawn::spot).toList();
        }
        Spot first = spawns.get(0).spot();
        Spot second = spawns.get(1).spot();
        double best = -1;
        for (int i = 0; i < spawns.size(); i++) {
            for (int j = i + 1; j < spawns.size(); j++) {
                double distance = spawns.get(i).spot().pos().distanceToSqr(spawns.get(j).spot().pos());
                if (distance > best) {
                    best = distance;
                    first = spawns.get(i).spot();
                    second = spawns.get(j).spot();
                }
            }
        }
        return List.of(first, second);
    }

    private static RoundEnd takes(Fighter fighter) {
        return new RoundEnd(Optional.of(fighter.id()), Component.literal("Round to " + fighter.name()).withStyle(ChatFormatting.GOLD));
    }

    private RoundEnd drawn(String why) {
        draws++;
        return new RoundEnd(Optional.empty(), Component.literal("Round drawn").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" (" + why + ")").withStyle(ChatFormatting.GRAY)));
    }

    private static Result win(Match match, Fighter winner, String detail) {
        return new Result(Set.of(winner.id()), Component.literal(winner.name() + " wins!").withStyle(ChatFormatting.GOLD), Component.literal(detail));
    }
}
