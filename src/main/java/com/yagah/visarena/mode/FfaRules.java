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
 * Free-for-all: everyone for themselves. First to the score limit in kills wins; when time runs out, the most kills
 * wins, with sudden death on a tie for the lead.
 */
public final class FfaRules implements ModeRules {
    private static final int SHOWN = 8;

    @Override
    public Map<UUID, Spot> startSpots(Match match) {
        List<ArenaMap.Spawn> spawns = new ArrayList<>(match.map().openSpawns());
        Collections.shuffle(spawns);
        Map<UUID, Spot> spots = new HashMap<>();
        int i = 0;
        for (UUID id : match.fighters().keySet()) {
            spots.put(id, spawns.get(i++ % spawns.size()).spot());
        }
        return spots;
    }

    /** The spawn farthest from every other living fighter. */
    @Override
    public Spot respawnSpot(Match match, ServerPlayer player) {
        List<ServerPlayer> others = match.online().stream().filter(other -> other != player && other.isAlive()).toList();
        return match.map().openSpawns().stream()
                .max(Comparator.comparingDouble(spawn -> others.stream()
                        .mapToDouble(other -> other.position().distanceToSqr(spawn.spot().pos()))
                        .min().orElse(0)))
                .orElseThrow()
                .spot();
    }

    @Override
    public Optional<Result> decided(Match match) {
        List<Fighter> present = match.fighters().values().stream().filter(Fighter::present).toList();
        if (present.size() == 1 && match.fighters().size() > 1) {
            return Optional.of(win(present.getFirst(), "Everyone else left"));
        }
        int limit = ArenaConfig.scoreLimit(ArenaMode.FFA);
        Optional<Fighter> reached = present.stream().filter(fighter -> fighter.kills() >= limit).findFirst();
        if (reached.isPresent()) {
            return Optional.of(win(reached.get(), reached.get().kills() + " kills"));
        }
        if (match.suddenDeath()) {
            return soleLeader(match).map(leader -> win(leader, "Sudden death"));
        }
        return Optional.empty();
    }

    @Override
    public Optional<Result> timeUp(Match match, boolean last) {
        Optional<Fighter> leader = soleLeader(match);
        if (leader.isPresent()) {
            return Optional.of(win(leader.get(), leader.get().kills() + " kills"));
        }
        if (last) {
            return Optional.of(new Result(Set.of(), Component.literal("Draw").withStyle(ChatFormatting.YELLOW),
                    Component.literal("Tied for the most kills")));
        }
        return Optional.empty();
    }

    @Override
    public List<Component> scoreLines(Match match) {
        List<Component> lines = new ArrayList<>();
        ranked(match).stream().limit(SHOWN).forEach(fighter -> lines.add(Component.literal(fighter.name() + " ")
                .withStyle(fighter.present() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY)
                .append(Component.literal(String.valueOf(fighter.kills())).withStyle(ChatFormatting.GOLD))));
        lines.add(Component.empty());
        lines.add(Component.literal("First to " + ArenaConfig.scoreLimit(ArenaMode.FFA) + " kills").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    /** Most kills first; fewer deaths breaks ties in the listing. */
    private static List<Fighter> ranked(Match match) {
        return match.fighters().values().stream()
                .sorted(Comparator.comparingInt(Fighter::kills).reversed().thenComparingInt(Fighter::deaths))
                .toList();
    }

    /** The one present fighter with the most kills, if nobody ties them. */
    private static Optional<Fighter> soleLeader(Match match) {
        List<Fighter> present = match.fighters().values().stream().filter(Fighter::present).toList();
        int best = present.stream().mapToInt(Fighter::kills).max().orElse(0);
        List<Fighter> leaders = present.stream().filter(fighter -> fighter.kills() == best).toList();
        return leaders.size() == 1 ? Optional.of(leaders.getFirst()) : Optional.empty();
    }

    private static Result win(Fighter winner, String detail) {
        return new Result(Set.of(winner.id()), Component.literal(winner.name() + " wins!").withStyle(ChatFormatting.GOLD),
                Component.literal(detail));
    }
}
