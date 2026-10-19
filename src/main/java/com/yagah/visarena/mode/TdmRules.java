package com.yagah.visarena.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.map.Spot;
import com.yagah.visarena.map.TeamColor;
import com.yagah.visarena.match.Fighter;
import com.yagah.visarena.match.Match;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Team deathmatch: red against blue (the match deals the teams). Every kill scores for the killer's team; first team to
 * the score limit wins. When time runs out the team with more kills wins, with sudden death on a tie. A team with
 * nobody left loses.
 */
public final class TdmRules implements ModeRules {
    private static final int SHOWN = 6;

    @Override
    public Map<UUID, Spot> startSpots(Match match) {
        Map<UUID, Spot> spots = new HashMap<>();
        for (TeamColor team : TeamColor.values()) {
            List<ArenaMap.Spawn> spawns = new ArrayList<>(spawns(match, team));
            Collections.shuffle(spawns);
            int i = 0;
            for (Fighter fighter : members(match, team)) {
                spots.put(fighter.id(), spawns.get(i++ % spawns.size()).spot());
            }
        }
        return spots;
    }

    /** Their team's spawn farthest from every living enemy. */
    @Override
    public Spot respawnSpot(Match match, ServerPlayer player) {
        Optional<TeamColor> team = match.fighters().get(player.getUUID()).team();
        List<ServerPlayer> enemies = match.online().stream()
                .filter(other -> other != player && other.isAlive() && !match.fighters().get(other.getUUID()).team().equals(team))
                .toList();
        return spawns(match, team.orElse(TeamColor.RED)).stream()
                .max(Comparator.comparingDouble(spawn -> enemies.stream()
                        .mapToDouble(enemy -> enemy.position().distanceToSqr(spawn.spot().pos()))
                        .min().orElse(0)))
                .orElseThrow()
                .spot();
    }

    @Override
    public Optional<Result> decided(Match match) {
        for (TeamColor team : TeamColor.values()) {
            if (members(match, team).stream().noneMatch(Fighter::present)) {
                TeamColor other = other(team);
                return Optional.of(win(match, other, "The " + team.id() + " team left"));
            }
        }
        Map<TeamColor, Integer> scores = scores(match);
        int limit = ArenaConfig.scoreLimit(ArenaMode.TDM);
        for (TeamColor team : TeamColor.values()) {
            if (scores.get(team) >= limit) {
                return Optional.of(win(match, team, scores.get(team) + " kills"));
            }
        }
        if (match.suddenDeath()) {
            return leader(scores).map(team -> win(match, team, "Sudden death"));
        }
        return Optional.empty();
    }

    @Override
    public Optional<Result> timeUp(Match match, boolean last) {
        Map<TeamColor, Integer> scores = scores(match);
        Optional<TeamColor> leader = leader(scores);
        if (leader.isPresent()) {
            return Optional.of(win(match, leader.get(), scores.get(leader.get()) + " to " + scores.get(other(leader.get()))));
        }
        if (last) {
            return Optional.of(new Result(Set.of(), Component.literal("Draw").withStyle(ChatFormatting.YELLOW),
                    Component.literal("The teams tied")));
        }
        return Optional.empty();
    }

    @Override
    public List<Component> scoreLines(Match match) {
        Map<TeamColor, Integer> scores = scores(match);
        List<Component> lines = new ArrayList<>();
        for (TeamColor team : TeamColor.values()) {
            lines.add(Component.literal(team.displayName() + " ").withStyle(team.color(), ChatFormatting.BOLD)
                    .append(Component.literal(String.valueOf(scores.get(team))).withStyle(ChatFormatting.WHITE)));
        }
        lines.add(Component.empty());
        match.fighters().values().stream()
                .sorted(Comparator.comparingInt(Fighter::kills).reversed().thenComparingInt(Fighter::deaths))
                .limit(SHOWN)
                .forEach(fighter -> lines.add((fighter.present() ? Match.name(fighter) : Match.name(fighter).withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(" " + fighter.kills()).withStyle(ChatFormatting.GOLD))));
        lines.add(Component.empty());
        lines.add(Component.literal("First to " + ArenaConfig.scoreLimit(ArenaMode.TDM) + " kills").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static List<ArenaMap.Spawn> spawns(Match match, TeamColor team) {
        return match.map().teamSpawns(team);
    }

    private static List<Fighter> members(Match match, TeamColor team) {
        return match.fighters().values().stream().filter(fighter -> fighter.team().equals(Optional.of(team))).toList();
    }

    /** Each team's kills. */
    private static Map<TeamColor, Integer> scores(Match match) {
        Map<TeamColor, Integer> scores = new EnumMap<>(TeamColor.class);
        for (TeamColor team : TeamColor.values()) {
            scores.put(team, members(match, team).stream().mapToInt(Fighter::kills).sum());
        }
        return scores;
    }

    /** The team ahead, if not tied. */
    private static Optional<TeamColor> leader(Map<TeamColor, Integer> scores) {
        int red = scores.get(TeamColor.RED);
        int blue = scores.get(TeamColor.BLUE);
        return red == blue ? Optional.empty() : Optional.of(red > blue ? TeamColor.RED : TeamColor.BLUE);
    }

    private static TeamColor other(TeamColor team) {
        return team == TeamColor.RED ? TeamColor.BLUE : TeamColor.RED;
    }

    private static Result win(Match match, TeamColor team, String detail) {
        Set<UUID> winners = members(match, team).stream().map(Fighter::id).collect(Collectors.toSet());
        return new Result(winners, Component.literal(team.displayName() + " team wins!").withStyle(team.color(), ChatFormatting.BOLD),
                Component.literal(detail));
    }
}
