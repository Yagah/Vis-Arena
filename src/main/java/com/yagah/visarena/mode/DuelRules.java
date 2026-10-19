package com.yagah.visarena.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 1v1: rounds. Whoever is left standing takes the round; when a round's time runs out, the one with more health left
 * (as a share of their max) takes it, or nobody on a tie. First to the score limit in rounds wins the match. Drawn
 * rounds can't go on forever: after {@link #ROUND_CAP} times the limit, the leader wins or it's a draw.
 */
public final class DuelRules implements ModeRules {
    private static final int ROUND_CAP = 3;

    @Override
    public boolean rounds() {
        return true;
    }

    /** The spawns, shuffled, one each (the two farthest apart if there are more). */
    @Override
    public Map<UUID, Spot> startSpots(Match match) {
        List<ArenaMap.Spawn> spawns = new ArrayList<>(match.map().spawns());
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
        return match.map().spawns().stream()
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
        if (!timeUp) {
            return Optional.empty();
        }
        // Time's up: the healthier fighter, by share of max health, takes it.
        Map<Fighter, Float> health = new HashMap<>();
        for (ServerPlayer player : match.online()) {
            Fighter fighter = match.fighters().get(player.getUUID());
            if (standing.contains(fighter)) {
                health.put(fighter, player.getHealth() / player.getMaxHealth());
            }
        }
        float best = health.values().stream().max(Float::compare).orElse(0f);
        List<Fighter> leaders = health.entrySet().stream().filter(entry -> entry.getValue() == best).map(Map.Entry::getKey).toList();
        return Optional.of(leaders.size() == 1 ? takes(leaders.getFirst()) : drawn("Time's up, level on health"));
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
        if (match.round() >= limit * ROUND_CAP) {
            return timeUp(match, true);
        }
        return Optional.empty();
    }

    /** Only reached at the round cap: the one with more rounds, else a draw. */
    @Override
    public Optional<Result> timeUp(Match match, boolean last) {
        List<Fighter> ranked = match.fighters().values().stream()
                .sorted(Comparator.comparingInt(Fighter::roundsWon).reversed()).toList();
        if (ranked.size() > 1 && ranked.get(0).roundsWon() > ranked.get(1).roundsWon()) {
            return Optional.of(win(match, ranked.getFirst(), score(match)));
        }
        return Optional.of(new Result(Set.of(), Component.literal("Draw").withStyle(ChatFormatting.YELLOW), Component.literal(score(match))));
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

    private static RoundEnd drawn(String why) {
        return new RoundEnd(Optional.empty(), Component.literal("Round drawn").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" (" + why + ")").withStyle(ChatFormatting.GRAY)));
    }

    private static Result win(Match match, Fighter winner, String detail) {
        return new Result(Set.of(winner.id()), Component.literal(winner.name() + " wins!").withStyle(ChatFormatting.GOLD), Component.literal(detail));
    }
}
