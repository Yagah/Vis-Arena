package com.yagah.visarena.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * A sidebar for one group of players (a match), sent straight to them with vanilla scoreboard packets. The server's
 * real scoreboard never holds it, so two matches each see their own and nobody else sees either. Each line is a score
 * with its own text and the numbers hidden; only lines that changed are resent.
 */
public final class Sidebar {
    private final Objective objective;
    private final List<Component> lines = new ArrayList<>();

    public Sidebar(String name, Component title) {
        this.objective = new Objective(new Scoreboard(), name, ObjectiveCriteria.DUMMY, title,
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
    }

    /** Shows the sidebar, with its current lines, to a player. */
    public void show(ServerPlayer player) {
        player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        for (int i = 0; i < lines.size(); i++) {
            player.connection.send(line(i));
        }
    }

    public void hide(ServerPlayer player) {
        player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
    }

    /** Sets the lines, top to bottom, sending the viewers only the lines that changed. */
    public void update(List<Component> next, Iterable<ServerPlayer> viewers) {
        for (int i = 0; i < Math.max(next.size(), lines.size()); i++) {
            if (i >= next.size()) {
                ClientboundResetScorePacket reset = new ClientboundResetScorePacket(owner(i), objective.getName());
                viewers.forEach(player -> player.connection.send(reset));
            } else if (i >= lines.size() || !next.get(i).equals(lines.get(i))) {
                ClientboundSetScorePacket packet = line(i, next);
                viewers.forEach(player -> player.connection.send(packet));
            }
        }
        lines.clear();
        lines.addAll(next);
    }

    private ClientboundSetScorePacket line(int i) {
        return line(i, lines);
    }

    /** Line i: a score that sorts it into place (higher is nearer the top), shown as its text. */
    private ClientboundSetScorePacket line(int i, List<Component> from) {
        return new ClientboundSetScorePacket(owner(i), objective.getName(), from.size() - i, Optional.of(from.get(i)), Optional.empty());
    }

    private static String owner(int i) {
        return "line" + i;
    }
}
