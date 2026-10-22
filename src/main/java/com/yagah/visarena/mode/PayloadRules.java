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
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.joml.Vector3f;

import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.hud.Titles;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.map.SiegeSide;
import com.yagah.visarena.map.Spot;
import com.yagah.visarena.map.TeamColor;
import com.yagah.visarena.match.Fighter;
import com.yagah.visarena.match.Match;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Payload: two halves on one track. In each, one side attacks, pushing the payload along the map's track by standing
 * near it, and the other defends; red attacks first, then they swap.
 * <p>
 * The payload is only a distance along the track, decided on the server: no physics, so nothing can snag, sink or
 * shove it. What players see is a translucent glass shell with a glowing core ({@link PayloadCart}, vanilla block
 * displays: no hitbox, so no ability can touch it, and standing inside it shields nobody), put where that distance
 * says every tick and gliding smoothly between.
 * <ul>
 * <li>Attackers near it push it: faster with two, faster again with three (the cap). Attackers near it slowly heal.</li>
 * <li>Any defender near it stops it, however many attackers there are.</li>
 * <li>Left with no attacker near for a while, it rolls back, never past the last checkpoint.</li>
 * <li>A checkpoint adds time to the clock, locks the progress in, and moves both sides to the next stage's spawns.</li>
 * <li>Out of time with an attacker at it: overtime, as long as an attacker stays near (a moment's gap ends it).</li>
 * </ul>
 * Winning compares time used: both delivered, the faster wins; one delivered, it wins; neither, the farther push wins.
 * The second half ends early only once it can't win: the first team delivered, and the second has gone past that time.
 */
public final class PayloadRules implements ModeRules {
    /** How often (ticks) the push zone's ring is drawn. */
    private static final int RING_TICKS = 10;
    private static final int RING_POINTS = 16;
    /** How often (ticks) the cart is moved; it glides over three ticks, so every other tick still looks smooth. */
    private static final int MOVE_TICKS = 2;
    /** How high the payload floats over the track, and how far it bobs. */
    private static final double FLOAT = 0.6;
    private static final double BOB = 0.12;

    /** One finished half: who attacked, whether they delivered, the time it took (or the whole half), how far it got. */
    private record Half(TeamColor attackers, boolean delivered, int ticks, double share) {
    }

    /** What the payload is doing, for the HUD. */
    private enum Motion { IDLE, PUSHING, CONTESTED, ROLLING_BACK }

    private final List<Half> halves = new ArrayList<>();
    private final ServerBossEvent bar = new ServerBossEvent(Component.literal("Payload"), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.NOTCHED_10);
    @Nullable
    private Match match;
    @Nullable
    private Track track;
    @Nullable
    private PayloadCart shell;
    @Nullable
    private PayloadCart core;
    /** Distance along the track, the last checkpoint passed, and the next one's index. */
    private double progress;
    private double locked;
    private int nextCheckpoint;
    /** Checkpoints passed this half: picks the spawns. */
    private int stage;
    /** The tick an attacker was last near it. */
    private int lastContact;
    private boolean overtimeAnnounced;
    private Motion motion = Motion.IDLE;
    private int pushers;

    // ---- The track ----

    /** The map's track as straight runs between points, with each point's distance from the start. */
    private record Track(List<Vec3> points, double[] distances, List<Double> checkpoints) {
        static Track of(List<ArenaMap.TrackPoint> track) {
            List<Vec3> points = track.stream().map(ArenaMap.TrackPoint::pos).toList();
            double[] distances = new double[points.size()];
            List<Double> checkpoints = new ArrayList<>();
            for (int i = 1; i < points.size(); i++) {
                distances[i] = distances[i - 1] + points.get(i).distanceTo(points.get(i - 1));
                if (track.get(i).checkpoint() && i < points.size() - 1) {
                    checkpoints.add(distances[i]);
                }
            }
            return new Track(points, distances, checkpoints);
        }

        double length() {
            return distances[distances.length - 1];
        }

        /** The run "d" falls on (the index of its first point). */
        private int run(double d) {
            int i = 0;
            while (i < distances.length - 2 && distances[i + 1] < d) {
                i++;
            }
            return i;
        }

        Vec3 at(double d) {
            int i = run(d);
            double span = distances[i + 1] - distances[i];
            double t = span <= 0 ? 0 : Mth.clamp((d - distances[i]) / span, 0, 1);
            return points.get(i).lerp(points.get(i + 1), t);
        }

        /** The way the track heads at "d", as a yaw in degrees. */
        float yaw(double d) {
            int i = run(d);
            Vec3 along = points.get(i + 1).subtract(points.get(i));
            return (float) (Mth.atan2(-along.x, along.z) * Mth.RAD_TO_DEG);
        }
    }

    // ---- Sides ----

    private static TeamColor attackers(Match match) {
        return match.round() == 1 ? TeamColor.RED : TeamColor.BLUE;
    }

    private static TeamColor defenders(Match match) {
        return other(attackers(match));
    }

    private static TeamColor other(TeamColor team) {
        return team == TeamColor.RED ? TeamColor.BLUE : TeamColor.RED;
    }

    private static SiegeSide side(Match match, Fighter fighter) {
        return fighter.team().equals(Optional.of(attackers(match))) ? SiegeSide.ATTACK : SiegeSide.DEFEND;
    }

    @Override
    public boolean rounds() {
        return true;
    }

    @Override
    public String roundName() {
        return "Half";
    }

    @Override
    public boolean roundPoints() {
        return false;
    }

    @Override
    public OptionalInt respawnDelay(Match match, Fighter fighter) {
        int delay = ArenaConfig.ticks(side(match, fighter) == SiegeSide.ATTACK
                ? ArenaConfig.PAYLOAD_ATTACKER_RESPAWN_SECONDS : ArenaConfig.PAYLOAD_DEFENDER_RESPAWN_SECONDS);
        return OptionalInt.of(match.underdog(fighter) ? (int) Math.round(delay * ArenaConfig.UNDERDOG_RESPAWN_MULTIPLIER.get()) : delay);
    }

    /** Each side on its stage-0 spawns, spread over them. */
    @Override
    public Map<UUID, Spot> startSpots(Match match) {
        Map<UUID, Spot> spots = new HashMap<>();
        for (SiegeSide side : SiegeSide.values()) {
            List<ArenaMap.Spawn> spawns = new ArrayList<>(match.map().stageSpawns(side, 0));
            Collections.shuffle(spawns);
            int i = 0;
            for (Fighter fighter : match.fighters().values()) {
                if (side(match, fighter) == side) {
                    spots.put(fighter.id(), spawns.get(i++ % spawns.size()).spot());
                }
            }
        }
        return spots;
    }

    /** Their side's spawn for the current stage farthest from every living enemy. */
    @Override
    public Spot respawnSpot(Match match, ServerPlayer player) {
        SiegeSide side = side(match, match.fighters().get(player.getUUID()));
        List<ServerPlayer> enemies = match.online().stream()
                .filter(other -> other.isAlive() && side(match, match.fighters().get(other.getUUID())) != side)
                .toList();
        return match.map().stageSpawns(side, stage).stream()
                .max(Comparator.comparingDouble(spawn -> enemies.stream()
                        .mapToDouble(enemy -> enemy.position().distanceToSqr(spawn.spot().pos()))
                        .min().orElse(0)))
                .orElseThrow()
                .spot();
    }

    // ---- The payload ----

    /** A fresh half: the payload back at the start, its cart in the attackers' colour. */
    @Override
    public void roundStarted(Match match) {
        this.match = match;
        removeCart();
        track = Track.of(match.map().track());
        progress = 0;
        locked = 0;
        nextCheckpoint = 0;
        stage = 0;
        lastContact = match.ticks();
        overtimeAnnounced = false;
        motion = Motion.IDLE;
        pushers = 0;
        ServerLevel level = match.level();
        Vec3 at = track.at(0);
        int glow = attackers(match) == TeamColor.RED ? 0xFF5555 : 0x5577FF;
        shell = PayloadCart.spawn(level, at, "minecraft:light_gray_stained_glass", 1.4f, (float) FLOAT + 0.7f, -1);
        core = PayloadCart.spawn(level, at, "minecraft:sea_lantern", 0.6f, (float) FLOAT + 0.7f, glow);
        placeCart();
        bar.removeAllPlayers();
        match.online().forEach(bar::addPlayer);
        updateBar();
    }

    private void removeCart() {
        if (shell != null) {
            shell.discard();
            shell = null;
        }
        if (core != null) {
            core.discard();
            core = null;
        }
    }

    @Override
    public void tick(Match match) {
        if (track == null) {
            return;
        }
        int now = match.ticks();
        Vec3 at = track.at(progress);
        double radius = ArenaConfig.PAYLOAD_RADIUS.get();
        List<ServerPlayer> nearAttackers = new ArrayList<>();
        boolean contested = false;
        for (ServerPlayer player : standing(match)) {
            if (player.position().distanceToSqr(at) <= radius * radius) {
                if (side(match, match.fighters().get(player.getUUID())) == SiegeSide.ATTACK) {
                    nearAttackers.add(player);
                } else {
                    contested = true;
                }
            }
        }
        pushers = nearAttackers.size();
        if (pushers > 0) {
            lastContact = now;
        }
        if (contested) {
            motion = Motion.CONTESTED;
        } else if (pushers > 0) {
            double multiplier = pushers >= 3 ? ArenaConfig.PAYLOAD_THREE_PUSHERS.get() : pushers == 2 ? ArenaConfig.PAYLOAD_TWO_PUSHERS.get() : 1;
            progress = Math.min(track.length(), progress + ArenaConfig.PAYLOAD_SPEED.get() * multiplier / 20);
            motion = Motion.PUSHING;
        } else if (now - lastContact >= ArenaConfig.ticks(ArenaConfig.PAYLOAD_ROLLBACK_DELAY_SECONDS) && progress > locked) {
            progress = Math.max(locked, progress - ArenaConfig.PAYLOAD_ROLLBACK_SPEED.get() / 20);
            motion = Motion.ROLLING_BACK;
        } else {
            motion = Motion.IDLE;
        }
        reachCheckpoints(match);
        if (progress >= track.length()) {
            match.requestCheck();
        }
        if (now % 20 == 0) {
            float heal = ArenaConfig.PAYLOAD_HEAL_PER_SECOND.get().floatValue();
            nearAttackers.forEach(player -> player.heal(heal));
        }
        if (now % RING_TICKS == 0) {
            drawRing(match.level(), track.at(progress), radius);
        }
        if (now % 5 == 0) {
            updateBar();
        }
        if (now % MOVE_TICKS == 0) {
            placeCart();
        }
    }

    /** Each checkpoint passed: progress locked in, time added, both sides on to the next stage's spawns. */
    private void reachCheckpoints(Match match) {
        while (track != null && nextCheckpoint < track.checkpoints().size() && progress >= track.checkpoints().get(nextCheckpoint)) {
            locked = track.checkpoints().get(nextCheckpoint);
            nextCheckpoint++;
            stage++;
            int bonus = ArenaConfig.PAYLOAD_CHECKPOINT_SECONDS.get();
            match.addTime(ArenaConfig.ticks(ArenaConfig.PAYLOAD_CHECKPOINT_SECONDS));
            overtimeAnnounced = false;
            for (ServerPlayer player : match.online()) {
                Titles.show(player, Component.literal("Checkpoint " + nextCheckpoint + " reached").withStyle(ChatFormatting.GOLD),
                        Component.literal(bonus > 0 ? "+" + bonus + " seconds" : ""), 30);
                player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.8f, 1.2f);
            }
        }
    }

    /** The cart where the track says, turned along it and bobbing gently. */
    private void placeCart() {
        if (track == null || match == null) {
            return;
        }
        Vec3 at = track.at(progress);
        float yaw = track.yaw(progress);
        double bob = Math.sin(match.ticks() * Math.PI / 40) * BOB;
        for (PayloadCart cart : new PayloadCart[] {shell, core}) {
            if (cart != null && !cart.isRemoved()) {
                cart.moveTo(at.x, at.y + bob, at.z, yaw, 0);
            }
        }
    }

    /** The push zone, as a ring of dust on the ground: the attackers' colour, or red while contested. */
    private void drawRing(ServerLevel level, Vec3 at, double radius) {
        if (match == null) {
            return;
        }
        int rgb = motion == Motion.CONTESTED ? 0xFF3333 : attackers(match) == TeamColor.RED ? 0xFF7777 : 0x7799FF;
        DustParticleOptions dust = new DustParticleOptions(new Vector3f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f), 1.2f);
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = i * Mth.TWO_PI / RING_POINTS;
            level.sendParticles(dust, at.x + Math.cos(angle) * radius, at.y + 0.15, at.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
    }

    private void updateBar() {
        if (match == null || track == null) {
            return;
        }
        String state = switch (motion) {
            case PUSHING -> "Pushing" + (pushers > 1 ? " x" + Math.min(pushers, 3) : "");
            case CONTESTED -> "Contested";
            case ROLLING_BACK -> "Rolling back";
            case IDLE -> "Waiting";
        };
        TeamColor attacking = attackers(match);
        bar.setName(Component.literal("Payload " + percent(share()) + "  ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(match.overtime() ? "OVERTIME" : state)
                        .withStyle(match.overtime() || motion == Motion.CONTESTED ? ChatFormatting.RED : attacking.color())));
        bar.setColor(attacking == TeamColor.RED ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.BLUE);
        bar.setProgress((float) Mth.clamp(share(), 0, 1));
    }

    /** Fighters in the fight right now: online, and not waiting to respawn. */
    private static List<ServerPlayer> standing(Match match) {
        return match.online().stream().filter(player -> !match.fighters().get(player.getUUID()).out()).toList();
    }

    /** How far along the track it is, from 0 to 1. */
    private double share() {
        return track == null || track.length() <= 0 ? 0 : progress / track.length();
    }

    @Override
    public void finished(Match match) {
        removeCart();
        bar.removeAllPlayers();
    }

    // ---- Halves and the result ----

    @Override
    public Optional<RoundEnd> roundOver(Match match, boolean timeUp) {
        if (track == null) {
            return Optional.empty();
        }
        TeamColor attacking = attackers(match);
        TeamColor defending = defenders(match);
        if (progress >= track.length()) {
            halves.add(new Half(attacking, true, match.elapsed(), 1));
            return Optional.of(new RoundEnd(Optional.empty(), Component.literal(attacking.displayName() + " delivered the payload in "
                    + clock(match.elapsed())).withStyle(attacking.color(), ChatFormatting.BOLD)));
        }
        if (halves.size() == 1 && settled(match)) {
            halves.add(new Half(attacking, false, match.elapsed(), share()));
            return Optional.of(new RoundEnd(Optional.empty(), Component.literal(defending.displayName() + " held past "
                    + clock(halves.getFirst().ticks())).withStyle(ChatFormatting.GOLD)));
        }
        if (!timeUp) {
            return Optional.empty();
        }
        // Out of time: overtime while an attacker is still at it (a moment's gap allowed), else the defenders held.
        int grace = (int) Math.round(ArenaConfig.PAYLOAD_OVERTIME_GRACE_SECONDS.get() * 20);
        if (match.ticks() - lastContact <= grace) {
            if (!overtimeAnnounced) {
                overtimeAnnounced = true;
                for (ServerPlayer player : match.online()) {
                    Titles.show(player, Component.literal("OVERTIME").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                            Component.literal("Stay on the payload!"), 30);
                }
            }
            return Optional.empty();
        }
        halves.add(new Half(attacking, false, match.elapsed(), share()));
        return Optional.of(new RoundEnd(Optional.empty(), Component.literal(defending.displayName() + " held! " + attacking.displayName()
                + " pushed it " + percent(share())).withStyle(defending.color(), ChatFormatting.BOLD)));
    }

    /** In the second half, true once it can't win: the first team delivered and these have gone past that time. */
    private boolean settled(Match match) {
        Half first = halves.getFirst();
        return first.delivered() && progress < (track == null ? 0 : track.length()) && match.elapsed() > first.ticks();
    }

    /** After the first half: the other team attacks next, and what they have to beat. */
    @Override
    public Optional<Component> roundSubtitle(Match match) {
        if (halves.size() != 1) {
            return Optional.empty();
        }
        Half first = halves.getFirst();
        TeamColor next = other(first.attackers());
        return Optional.of(Component.literal(next.displayName() + " attacks next: ").withStyle(next.color())
                .append(Component.literal(first.delivered() ? "deliver it faster than " + clock(first.ticks())
                        : "push it farther than " + percent(first.share())).withStyle(ChatFormatting.WHITE)));
    }

    @Override
    public Optional<Result> decided(Match match) {
        for (TeamColor team : TeamColor.values()) {
            if (match.fighters().values().stream().noneMatch(f -> f.present() && f.team().equals(Optional.of(team)))) {
                return Optional.of(win(match, other(team), "The " + team.id() + " team left"));
            }
        }
        if (halves.size() < 2) {
            return Optional.empty();
        }
        Half first = halves.get(0);
        Half second = halves.get(1);
        if (first.delivered() != second.delivered()) {
            Half better = first.delivered() ? first : second;
            return Optional.of(win(match, better.attackers(), "Delivered in " + clock(better.ticks())));
        }
        if (first.delivered()) {
            if (first.ticks() == second.ticks()) {
                return Optional.of(draw("Both delivered in " + clock(first.ticks())));
            }
            Half faster = first.ticks() < second.ticks() ? first : second;
            Half slower = faster == first ? second : first;
            return Optional.of(win(match, faster.attackers(), clock(faster.ticks()) + " to " + clock(slower.ticks())));
        }
        if (Math.abs(first.share() - second.share()) < 1e-6) {
            return Optional.of(draw("Both pushed it " + percent(first.share())));
        }
        Half farther = first.share() > second.share() ? first : second;
        Half shorter = farther == first ? second : first;
        return Optional.of(win(match, farther.attackers(), percent(farther.share()) + " to " + percent(shorter.share())));
    }

    /** Not used: halves always end with a result. */
    @Override
    public Optional<Result> timeUp(Match match, boolean last) {
        return Optional.of(draw("Time"));
    }

    @Override
    public List<Component> scoreLines(Match match) {
        List<Component> lines = new ArrayList<>();
        TeamColor attacking = attackers(match);
        lines.add(Component.literal(attacking.displayName() + " pushing").withStyle(attacking.color(), ChatFormatting.BOLD));
        lines.add(Component.literal("Payload " + percent(share())).withStyle(ChatFormatting.WHITE));
        if (track != null && !track.checkpoints().isEmpty()) {
            lines.add(Component.literal("Checkpoints " + nextCheckpoint + " of " + track.checkpoints().size()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.empty());
        for (Half half : halves) {
            lines.add(Component.literal(half.attackers().displayName() + ": ").withStyle(half.attackers().color())
                    .append(Component.literal(half.delivered() ? "delivered in " + clock(half.ticks()) : percent(half.share()))
                            .withStyle(ChatFormatting.WHITE)));
        }
        if (halves.size() == 1 && match.round() == 2) {
            Half first = halves.getFirst();
            lines.add(Component.literal("To beat: " + (first.delivered() ? "deliver in " + clock(first.ticks()) : "over " + percent(first.share())))
                    .withStyle(ChatFormatting.GOLD));
        }
        return lines;
    }

    private static Result win(Match match, TeamColor team, String detail) {
        Set<UUID> winners = match.fighters().values().stream().filter(f -> f.team().equals(Optional.of(team)))
                .map(Fighter::id).collect(Collectors.toSet());
        return new Result(winners, Component.literal(team.displayName() + " team wins!").withStyle(team.color(), ChatFormatting.BOLD),
                Component.literal(detail));
    }

    private static Result draw(String detail) {
        return new Result(Set.of(), Component.literal("Draw").withStyle(ChatFormatting.YELLOW), Component.literal(detail));
    }

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }

    private static String clock(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
