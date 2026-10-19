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

import com.yagah.vismod.data.VisDataSync;
import com.yagah.vismod.team.VisAllies;
import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.VisArena;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.map.SiegeSide;
import com.yagah.visarena.map.Spot;
import com.yagah.visarena.map.TeamColor;
import com.yagah.visarena.match.Fighter;
import com.yagah.visarena.match.Match;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Core Siege: two halves on one core. Red attacks it in the first while blue defends; then they swap. The core is a
 * vanilla shulker standing in the map's core zone, on the defending team (so defenders' abilities pass it by); hits on it
 * drain its own health instead of hurting it. While any defender stands in the zone it's shielded; it throws attackers
 * back at 75%, 50% and 25% (then is briefly immune); left alone by attackers it slowly heals, never past its last mark;
 * defenders near it regain Vis faster. A half ends when the core breaks or time runs out. A break beats a hold; the
 * faster break wins; with no break, the more damage; level is a draw.
 */
@EventBusSubscriber(modid = VisArena.MODID)
public final class SiegeRules implements ModeRules {
    /** Every live core, to route hits on it to its match. */
    private static final Map<UUID, SiegeRules> CORES = new HashMap<>();
    /** The health marks, as shares of the core's health, where it sends out a shockwave. */
    private static final float[] MARKS = {0.75f, 0.5f, 0.25f};
    /** How often (ticks) the zone is checked, the core heals and the bar updates. */
    private static final int CHECK_TICKS = 5;

    /** One finished half: who attacked, whether they broke the core, how long it took (or the whole half), the damage share. */
    private record Half(TeamColor attackers, boolean broke, int ticks, float damage) {
    }

    /** The action bar a fighter was last sent, and when. */
    private record Shown(Component text, int at) {
    }

    /** Ticks before an unchanged action bar is resent (the client fades it after about three seconds). */
    private static final int ACTION_BAR_REFRESH = 40;

    private final List<Half> halves = new ArrayList<>();
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final ServerBossEvent bar = new ServerBossEvent(Component.literal("Core"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
    @Nullable
    private Match match;
    @Nullable
    private Shulker core;
    private Vec3 corePos = Vec3.ZERO;
    private AABB zone = new AABB(0, 0, 0, 0, 0, 0);
    private AABB near = zone;
    private float health;
    private float maxHealth = 1;
    /** How many health marks it has fallen past this half, and how high it may heal back. */
    private int marksPassed;
    private float regenCap = 1;
    private int immuneUntil;
    private int lastAttackerNear;
    private boolean exposed = true;

    /** Red attacks in the first half, blue in the second. */
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
    public OptionalInt respawnDelay(Match match, Fighter fighter) {
        int delay = ArenaConfig.ticks(side(match, fighter) == SiegeSide.ATTACK
                ? ArenaConfig.ATTACKER_RESPAWN_SECONDS : ArenaConfig.DEFENDER_RESPAWN_SECONDS);
        // The short team (uneven sides) is back in the fight sooner.
        return OptionalInt.of(match.underdog(fighter) ? (int) Math.round(delay * ArenaConfig.UNDERDOG_RESPAWN_MULTIPLIER.get()) : delay);
    }

    @Override
    public Map<UUID, Spot> startSpots(Match match) {
        Map<UUID, Spot> spots = new HashMap<>();
        for (SiegeSide side : SiegeSide.values()) {
            List<ArenaMap.Spawn> spawns = new ArrayList<>(match.map().siegeSpawns(side));
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

    /** Their side's spawn farthest from every living enemy. */
    @Override
    public Spot respawnSpot(Match match, ServerPlayer player) {
        SiegeSide side = side(match, match.fighters().get(player.getUUID()));
        List<ServerPlayer> enemies = match.online().stream()
                .filter(other -> other.isAlive() && side(match, match.fighters().get(other.getUUID())) != side)
                .toList();
        return match.map().siegeSpawns(side).stream()
                .max(Comparator.comparingDouble(spawn -> enemies.stream()
                        .mapToDouble(enemy -> enemy.position().distanceToSqr(spawn.spot().pos()))
                        .min().orElse(0)))
                .orElseThrow()
                .spot();
    }

    // ---- The core ----

    /** A fresh core for the half, at full health, on the defending team. */
    @Override
    public void roundStarted(Match match) {
        this.match = match;
        removeCore();
        BoundingBox box = match.map().core().orElseThrow();
        zone = AABB.of(box);
        near = zone.inflate(ArenaConfig.NEAR_RADIUS.get());
        ServerLevel level = match.level();
        BlockPos spot = standingSpot(level, box);
        corePos = Vec3.atBottomCenterOf(spot);
        maxHealth = ArenaConfig.CORE_HEALTH.get();
        health = maxHealth;
        marksPassed = 0;
        regenCap = 1;
        immuneUntil = 0;
        lastAttackerNear = match.ticks();
        exposed = true;
        if (!level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP)) {
            VisArena.LOGGER.warn("Map {}: no solid floor in the middle of the core zone; the core floats at {}", match.map().name(), spot);
        }
        spawnCore();
        bar.removeAllPlayers();
        match.online().forEach(bar::addPlayer);
        updateBar();
    }

    /**
     * Where the core stands: in the middle column of the zone, on the lowest open spot with a solid block under it
     * (so it doesn't matter whether the zone was marked from the ground or from the air above it), or the zone's floor.
     */
    private static BlockPos standingSpot(ServerLevel level, BoundingBox box) {
        int x = (box.minX() + box.maxX()) / 2;
        int z = (box.minZ() + box.maxZ()) / 2;
        for (int y = box.minY(); y <= box.maxY() + 1; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                return pos;
            }
        }
        return new BlockPos(x, box.minY(), z);
    }

    private void spawnCore() {
        if (match == null) {
            return;
        }
        Shulker shulker = EntityType.SHULKER.create(match.level());
        if (shulker == null) {
            return;
        }
        TeamColor defending = defenders(match);
        shulker.moveTo(corePos.x, corePos.y, corePos.z, 0, 0);
        shulker.setNoAi(true);
        shulker.setPersistenceRequired();
        shulker.setSilent(true);
        shulker.setGlowingTag(true);
        shulker.setVariant(Optional.of(defending == TeamColor.RED ? DyeColor.RED : DyeColor.BLUE));
        shulker.setCustomName(Component.literal(defending.displayName() + " core").withStyle(defending.color(), ChatFormatting.BOLD));
        shulker.setCustomNameVisible(true);
        match.level().addFreshEntity(shulker);
        // On the defenders' team: it glows their colour, and Vis Mod's ally check keeps their abilities off it.
        match.team(defending).ifPresent(team -> match.level().getScoreboard().addPlayerToTeam(shulker.getScoreboardName(), team));
        core = shulker;
        CORES.put(shulker.getUUID(), this);
    }

    private void removeCore() {
        if (core != null) {
            CORES.remove(core.getUUID());
            core.discard();
            core = null;
        }
    }

    @Override
    public void tick(Match match) {
        // Kept in place (pulls, pushes and absorbs can't take it), and brought back if something removed it.
        if (core == null || core.isRemoved()) {
            if (core != null) {
                CORES.remove(core.getUUID());
                core = null;
            }
            spawnCore();
        } else if (core.position().distanceToSqr(corePos) > 0.01) {
            core.teleportTo(corePos.x, corePos.y, corePos.z);
            core.setDeltaMovement(Vec3.ZERO);
        }
        int now = match.ticks();
        if (now % CHECK_TICKS == 0) {
            checkZone(match, now);
        }
        if (now % 20 == 0) {
            visBonus(match);
            if (halves.size() == 1 && settled(match)) {
                match.requestCheck();
            }
        }
        if (now % CHECK_TICKS == 0) {
            actionBars(match, now);
        }
    }

    /** Shielding (any defender in the zone), regeneration (no attacker near for a while), and the bar. */
    private void checkZone(Match match, int now) {
        boolean defended = false;
        boolean attackerNear = false;
        for (ServerPlayer player : standing(match)) {
            SiegeSide side = side(match, match.fighters().get(player.getUUID()));
            if (side == SiegeSide.DEFEND && player.getBoundingBox().intersects(zone)) {
                defended = true;
            } else if (side == SiegeSide.ATTACK && player.getBoundingBox().intersects(near)) {
                attackerNear = true;
            }
        }
        exposed = !defended;
        if (attackerNear) {
            lastAttackerNear = now;
        } else if (now - lastAttackerNear >= ArenaConfig.ticks(ArenaConfig.REGEN_DELAY_SECONDS) && health > 0) {
            float heal = (float) (maxHealth * ArenaConfig.REGEN_PERCENT_PER_SECOND.get() / 100 * CHECK_TICKS / 20);
            health = Math.min(health + heal, maxHealth * regenCap);
        }
        updateBar();
    }

    /** Defenders near their core regain Vis faster. */
    private void visBonus(Match match) {
        float bonus = ArenaConfig.DEFENDER_VIS_PER_SECOND.get().floatValue();
        if (bonus <= 0) {
            return;
        }
        for (ServerPlayer player : standing(match)) {
            if (side(match, match.fighters().get(player.getUUID())) == SiegeSide.DEFEND && player.getBoundingBox().intersects(near)) {
                VisDataSync.get(player).addEnergy(bonus);
                VisDataSync.syncEnergyIfChanged(player);
            }
        }
    }

    private void actionBars(Match match, int now) {
        boolean immune = now < immuneUntil;
        for (ServerPlayer player : standing(match)) {
            boolean attacking = side(match, match.fighters().get(player.getUUID())) == SiegeSide.ATTACK;
            Component text;
            if (immune) {
                text = Component.literal("Shockwave! The core is immune").withStyle(ChatFormatting.GOLD);
            } else if (attacking) {
                text = exposed ? Component.literal("Core exposed - attack!").withStyle(ChatFormatting.GREEN)
                        : Component.literal("Core shielded - clear the defenders out of the zone").withStyle(ChatFormatting.RED);
            } else {
                text = exposed ? Component.literal("Core exposed - get into the zone!").withStyle(ChatFormatting.RED)
                        : Component.literal("Holding the core").withStyle(ChatFormatting.GREEN);
            }
            // Sent when it changes, and refreshed before the client fades it out.
            Shown last = shown.get(player.getUUID());
            if (last == null || !last.text().equals(text) || now - last.at() >= ACTION_BAR_REFRESH) {
                player.displayClientMessage(text, true);
                shown.put(player.getUUID(), new Shown(text, now));
            }
        }
    }

    /** A hit on the core from an attacker counts against its health, if it's exposed and not immune. */
    private void hit(Entity owner, float amount) {
        if (match == null || match.state() != Match.State.PLAYING || health <= 0) {
            return;
        }
        Optional<Fighter> fighter = match.fighter(owner).filter(f -> f.present() && !f.out() && side(match, f) == SiegeSide.ATTACK);
        if (fighter.isEmpty()) {
            return;
        }
        ServerLevel level = match.level();
        if (!exposed || match.ticks() < immuneUntil) {
            level.playSound(null, corePos.x, corePos.y, corePos.z, SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8f, 1.2f);
            return;
        }
        health = Math.max(0, health - amount * ArenaConfig.CORE_DAMAGE_MULTIPLIER.get().floatValue());
        level.playSound(null, corePos.x, corePos.y, corePos.z, SoundEvents.SHULKER_HURT_CLOSED, SoundSource.HOSTILE, 1.0f, 0.8f);
        level.sendParticles(ParticleTypes.CRIT, corePos.x, corePos.y + 0.5, corePos.z, 8, 0.4, 0.4, 0.4, 0.2);
        while (marksPassed < MARKS.length && health / maxHealth <= MARKS[marksPassed] && health > 0) {
            shockwave(MARKS[marksPassed]);
            marksPassed++;
        }
        if (health <= 0) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, corePos.x, corePos.y + 0.5, corePos.z, 1, 0, 0, 0, 0);
            level.playSound(null, corePos.x, corePos.y, corePos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0f, 0.8f);
            match.requestCheck();
        }
        updateBar();
    }

    /** At a health mark: attackers near are thrown back, and the core is immune for a moment and won't heal past it. */
    private void shockwave(float mark) {
        if (match == null) {
            return;
        }
        regenCap = mark;
        immuneUntil = match.ticks() + ArenaConfig.ticks(ArenaConfig.SHOCKWAVE_IMMUNITY_SECONDS);
        ServerLevel level = match.level();
        level.sendParticles(ParticleTypes.SONIC_BOOM, corePos.x, corePos.y + 0.5, corePos.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.EXPLOSION, corePos.x, corePos.y + 0.5, corePos.z, 6, 2, 0.5, 2, 0);
        level.playSound(null, corePos.x, corePos.y, corePos.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.5f, 1.0f);
        double strength = ArenaConfig.SHOCKWAVE_STRENGTH.get();
        for (ServerPlayer player : standing(match)) {
            if (side(match, match.fighters().get(player.getUUID())) == SiegeSide.ATTACK && player.getBoundingBox().intersects(near)) {
                player.knockback(strength, corePos.x - player.getX(), corePos.z - player.getZ());
                Vec3 moving = player.getDeltaMovement();
                player.setDeltaMovement(moving.x, Math.max(moving.y, 0.5), moving.z);
                player.hurtMarked = true;
            }
        }
    }

    private void updateBar() {
        if (match == null) {
            return;
        }
        TeamColor defending = defenders(match);
        boolean immune = match.ticks() < immuneUntil;
        bar.setName(Component.literal(defending.displayName() + " core  " + percent(health / maxHealth) + "  ")
                .append(Component.literal(health <= 0 ? "Broken" : immune ? "Immune" : exposed ? "Exposed" : "Shielded")
                        .withStyle(health <= 0 || exposed && !immune ? ChatFormatting.RED : ChatFormatting.GREEN)));
        bar.setColor(defending == TeamColor.RED ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.BLUE);
        bar.setProgress(Math.clamp(health / maxHealth, 0f, 1f));
    }

    /** Fighters in the fight right now: online, and not waiting to respawn. */
    private static List<ServerPlayer> standing(Match match) {
        return match.online().stream().filter(player -> !match.fighters().get(player.getUUID()).out()).toList();
    }

    @Override
    public void finished(Match match) {
        removeCore();
        bar.removeAllPlayers();
    }

    // ---- Halves and the result ----

    /** The share of the core's health taken this half. */
    private float damage() {
        return Math.clamp(1 - health / maxHealth, 0f, 1f);
    }

    /**
     * In the second half, true once its result can't change the winner: the first attackers broke the core and this
     * half has run longer without a break, or they didn't and these attackers have already done more damage.
     */
    private boolean settled(Match match) {
        Half first = halves.getFirst();
        return first.broke() ? health > 0 && match.elapsed() > first.ticks() : damage() > first.damage();
    }

    @Override
    public Optional<RoundEnd> roundOver(Match match, boolean timeUp) {
        TeamColor attacking = attackers(match);
        TeamColor defending = defenders(match);
        if (health <= 0) {
            halves.add(new Half(attacking, true, match.elapsed(), 1));
            return Optional.of(new RoundEnd(Optional.empty(), Component.literal(attacking.displayName() + " broke the core in "
                    + clock(match.elapsed())).withStyle(attacking.color(), ChatFormatting.BOLD)));
        }
        if (halves.size() == 1 && settled(match)) {
            halves.add(new Half(attacking, false, match.elapsed(), damage()));
            Half first = halves.getFirst();
            return Optional.of(new RoundEnd(Optional.empty(), Component.literal(first.broke()
                    ? defending.displayName() + " held past " + clock(first.ticks())
                    : attacking.displayName() + " passed " + percent(first.damage())).withStyle(ChatFormatting.GOLD)));
        }
        if (timeUp) {
            halves.add(new Half(attacking, false, match.elapsed(), damage()));
            return Optional.of(new RoundEnd(Optional.empty(), Component.literal(defending.displayName() + " held the core! "
                    + attacking.displayName() + " dealt " + percent(damage())).withStyle(defending.color(), ChatFormatting.BOLD)));
        }
        return Optional.empty();
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
                .append(Component.literal(first.broke() ? "break it faster than " + clock(first.ticks())
                        : "deal more than " + percent(first.damage())).withStyle(ChatFormatting.WHITE)));
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
        if (first.broke() != second.broke()) {
            Half better = first.broke() ? first : second;
            return Optional.of(win(match, better.attackers(), "Broke the core in " + clock(better.ticks())));
        }
        if (first.broke()) {
            if (first.ticks() == second.ticks()) {
                return Optional.of(draw("Both broke the core in " + clock(first.ticks())));
            }
            Half faster = first.ticks() < second.ticks() ? first : second;
            Half slower = faster == first ? second : first;
            return Optional.of(win(match, faster.attackers(), clock(faster.ticks()) + " to " + clock(slower.ticks())));
        }
        if (first.damage() == second.damage()) {
            return Optional.of(draw("Both dealt " + percent(first.damage())));
        }
        Half more = first.damage() > second.damage() ? first : second;
        Half less = more == first ? second : first;
        return Optional.of(win(match, more.attackers(), percent(more.damage()) + " to " + percent(less.damage()) + " damage"));
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
        lines.add(Component.literal(attacking.displayName() + " attacking").withStyle(attacking.color(), ChatFormatting.BOLD));
        lines.add(Component.literal("Core " + percent(health / maxHealth)).withStyle(ChatFormatting.WHITE));
        lines.add(Component.empty());
        for (Half half : halves) {
            lines.add(Component.literal(half.attackers().displayName() + ": ").withStyle(half.attackers().color())
                    .append(Component.literal(half.broke() ? "broke it in " + clock(half.ticks()) : percent(half.damage()) + " damage")
                            .withStyle(ChatFormatting.WHITE)));
        }
        if (halves.size() == 1 && match.round() == 2) {
            Half first = halves.getFirst();
            lines.add(Component.literal("To beat: " + (first.broke() ? "break it in " + clock(first.ticks()) : "over " + percent(first.damage())))
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

    private static String percent(float share) {
        return Math.round(share * 100) + "%";
    }

    private static String clock(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    // ---- Events ----

    /**
     * Nothing hurts a core for real: every hit is cancelled, and an attacker's (Vis abilities, summons and projectiles
     * included, through Vis Mod's owner chain) counts against its own health. Runs after Vis Mod's ally check, which has
     * already cancelled the defenders' hits.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        SiegeRules rules = CORES.get(event.getEntity().getUUID());
        if (rules == null) {
            return;
        }
        event.setCanceled(true);
        Entity attacker = event.getSource().getEntity() != null ? event.getSource().getEntity() : event.getSource().getDirectEntity();
        if (attacker != null) {
            rules.hit(VisAllies.owner(attacker), event.getAmount());
        }
    }
}
