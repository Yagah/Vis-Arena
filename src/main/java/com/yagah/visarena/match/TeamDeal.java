package com.yagah.visarena.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.yagah.visarena.map.TeamColor;
import com.yagah.visarena.stats.ArenaStats;

/**
 * Splits a team match's players into red and blue. A side holds at most half the players, rounded up, so the teams
 * differ by one at most. Picks from the team screen are honoured in queue order while their side has room; everyone
 * else is placed strongest first, each onto the smaller side (the weaker one, by total rating, on a tie).
 */
final class TeamDeal {
    private TeamDeal() {
    }

    /** The most players one side may have in a match of this many. */
    static int cap(int players) {
        return (players + 1) / 2;
    }

    /** Each player's side, in the order given. */
    static Map<UUID, TeamColor> deal(List<UUID> players, Map<UUID, TeamColor> picks, ArenaStats stats) {
        Map<UUID, TeamColor> teams = new LinkedHashMap<>();
        Map<TeamColor, Integer> sizes = new EnumMap<>(TeamColor.class);
        Map<TeamColor, Double> strength = new EnumMap<>(TeamColor.class);
        for (TeamColor team : TeamColor.values()) {
            sizes.put(team, 0);
            strength.put(team, 0.0);
        }
        int cap = cap(players.size());
        List<UUID> undecided = new ArrayList<>();
        for (UUID id : players) {
            TeamColor pick = picks.get(id);
            if (pick != null && sizes.get(pick) < cap) {
                join(teams, sizes, strength, id, pick, rating(stats, id));
            } else {
                undecided.add(id);
            }
        }
        undecided.sort(Comparator.comparingDouble((UUID id) -> rating(stats, id)).reversed());
        for (UUID id : undecided) {
            TeamColor side = sizes.get(TeamColor.RED) < sizes.get(TeamColor.BLUE) ? TeamColor.RED
                    : sizes.get(TeamColor.BLUE) < sizes.get(TeamColor.RED) ? TeamColor.BLUE
                    : strength.get(TeamColor.RED) <= strength.get(TeamColor.BLUE) ? TeamColor.RED : TeamColor.BLUE;
            join(teams, sizes, strength, id, side, rating(stats, id));
        }
        // Back in the order given, so the match lists players as they queued.
        Map<UUID, TeamColor> ordered = new LinkedHashMap<>();
        players.forEach(id -> ordered.put(id, teams.get(id)));
        return ordered;
    }

    private static void join(Map<UUID, TeamColor> teams, Map<TeamColor, Integer> sizes, Map<TeamColor, Double> strength,
            UUID id, TeamColor team, double rating) {
        teams.put(id, team);
        sizes.merge(team, 1, Integer::sum);
        strength.merge(team, rating, Double::sum);
    }

    /**
     * A rough skill rating from a player's record in all modes: mostly their win rate, some their kills per death.
     * Both are pulled toward the middle for players with few games, so a newcomer rates as average (about 0.45).
     */
    static double rating(ArenaStats stats, UUID id) {
        ArenaStats.Record record = stats.player(id).map(player -> player.record(Optional.empty())).orElse(ArenaStats.Record.NONE);
        double winRate = (record.wins() + 1.0) / (record.played() + 2.0);
        double killsPerDeath = Math.min(3.0, (record.kills() + 1.0) / (record.deaths() + 1.0));
        return 0.7 * winRate + 0.1 * killsPerDeath;
    }
}
