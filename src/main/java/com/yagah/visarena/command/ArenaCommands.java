package com.yagah.visarena.command;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.yagah.visarena.VisArena;
import com.yagah.visarena.lobby.Lobby;
import com.yagah.visarena.map.ArenaData;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.map.SiegeSide;
import com.yagah.visarena.map.Spot;
import com.yagah.visarena.map.TeamColor;
import com.yagah.visarena.match.Matches;
import com.yagah.visarena.match.Queues;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /arena: the lobby spawn, builder mode, and marking arena maps. Operators only. */
@EventBusSubscriber(modid = VisArena.MODID)
public final class ArenaCommands {
    private static final DynamicCommandExceptionType NO_MAP = new DynamicCommandExceptionType(
            name -> Component.literal("No arena map named " + name));
    private static final DynamicCommandExceptionType NO_MODE = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown mode " + id + "; one of " + modeIds()));
    private static final DynamicCommandExceptionType OTHER_DIMENSION = new DynamicCommandExceptionType(
            name -> Component.literal("Map " + name + " is in another dimension"));

    private ArenaCommands() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("arena")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("lobby")
                        .then(Commands.literal("set").executes(ArenaCommands::setLobby)))
                .then(Commands.literal("build").executes(ArenaCommands::toggleBuild))
                .then(Commands.literal("start")
                        .then(modeArgument()
                                .executes(ctx -> startNow(ctx, false))
                                .then(mapArgument().executes(ctx -> startNow(ctx, true)))))
                .then(Commands.literal("stop").executes(ArenaCommands::stopAll))
                .then(Commands.literal("map")
                        .then(Commands.literal("list").executes(ArenaCommands::listMaps))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(ArenaCommands::createMap)))
                        .then(Commands.literal("delete")
                                .then(mapArgument().executes(ArenaCommands::deleteMap)))
                        .then(mapArgument()
                                .then(Commands.literal("check").executes(ArenaCommands::checkMap))
                                .then(Commands.literal("mode")
                                        .then(Commands.literal("add").then(modeArgument().executes(ctx -> setMode(ctx, true))))
                                        .then(Commands.literal("remove").then(modeArgument().executes(ctx -> setMode(ctx, false)))))
                                .then(Commands.literal("spawn")
                                        .then(Commands.literal("add")
                                                .executes(ctx -> addSpawn(ctx, Optional.empty(), Optional.empty()))
                                                .then(Commands.literal("red").executes(ctx -> addSpawn(ctx, Optional.of(TeamColor.RED), Optional.empty())))
                                                .then(Commands.literal("blue").executes(ctx -> addSpawn(ctx, Optional.of(TeamColor.BLUE), Optional.empty())))
                                                .then(Commands.literal("attack").executes(ctx -> addSpawn(ctx, Optional.empty(), Optional.of(SiegeSide.ATTACK))))
                                                .then(Commands.literal("defend").executes(ctx -> addSpawn(ctx, Optional.empty(), Optional.of(SiegeSide.DEFEND)))))
                                        .then(Commands.literal("clear").executes(ArenaCommands::clearSpawns)))
                                .then(Commands.literal("floor")
                                        .then(Commands.literal("none").executes(ctx -> setFloor(ctx, Optional.empty())))
                                        .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
                                                .executes(ctx -> setFloor(ctx, Optional.of(IntegerArgumentType.getInteger(ctx, "y"))))))
                                .then(Commands.literal("core")
                                        .then(Commands.literal("clear").executes(ArenaCommands::clearCore))
                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                        .executes(ArenaCommands::setCore))))
                                .then(Commands.literal("tp").executes(ArenaCommands::teleportToMap)))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> mapArgument() {
        return Commands.argument("map", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        ArenaData.get(ctx.getSource().getServer()).maps().stream().map(ArenaMap::name), builder));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> modeArgument() {
        return Commands.argument("mode", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(ArenaMode.values()).map(ArenaMode::id), builder));
    }

    private static String modeIds() {
        return Arrays.stream(ArenaMode.values()).map(ArenaMode::id).collect(Collectors.joining(", "));
    }

    private static ArenaMap map(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "map");
        return ArenaData.get(ctx.getSource().getServer()).map(name).orElseThrow(() -> NO_MAP.create(name));
    }

    /** The map, checked to be in the dimension the command runs in (spawns and cores are marked where you stand). */
    private static ArenaMap mapHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ArenaMap map = map(ctx);
        if (map.dimension() != ctx.getSource().getLevel().dimension()) {
            throw OTHER_DIMENSION.create(map.name());
        }
        return map;
    }

    private static void saved(CommandContext<CommandSourceStack> ctx) {
        ArenaData.get(ctx.getSource().getServer()).setDirty();
    }

    private static void success(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), true);
    }

    private static int setLobby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ArenaData.get(ctx.getSource().getServer()).setLobby(new ArenaData.Lobby(player.level().dimension(), Spot.of(player)));
        success(ctx, "Lobby spawn set here");
        return Command.SINGLE_SUCCESS;
    }

    /** Starts a match now from the mode's queue, even short of its fewest players (for testing). */
    private static int startNow(CommandContext<CommandSourceStack> ctx, boolean withMap) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "mode");
        ArenaMode mode = ArenaMode.byId(id).orElseThrow(() -> NO_MODE.create(id));
        Optional<ArenaMap> map = withMap ? Optional.of(map(ctx)) : Optional.empty();
        Optional<String> failure = Queues.startNow(ctx.getSource().getServer(), mode, map);
        if (failure.isPresent()) {
            ctx.getSource().sendFailure(Component.literal(failure.get()));
            return 0;
        }
        success(ctx, "Started a " + mode.displayName() + " match");
        return Command.SINGLE_SUCCESS;
    }

    private static int stopAll(CommandContext<CommandSourceStack> ctx) {
        int stopped = Matches.stopAll();
        success(ctx, "Stopped " + stopped + " match(es)");
        return stopped;
    }

    private static int toggleBuild(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Matches.inMatch(player)) {
            ctx.getSource().sendFailure(Component.literal("You're in a match; /arena stop it first"));
            return 0;
        }
        boolean builder = !Lobby.builder(player);
        Lobby.setBuilder(player, builder);
        ctx.getSource().sendSuccess(() -> Component.literal(builder
                ? "Builder mode on: creative, and the lobby leaves you alone. /arena build again to return."
                : "Builder mode off: back to the lobby"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listMaps(CommandContext<CommandSourceStack> ctx) {
        ArenaData data = ArenaData.get(ctx.getSource().getServer());
        if (data.maps().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No arena maps yet. Make one with /arena map create <name>."), false);
            return 0;
        }
        MutableComponent text = Component.literal("Arena maps:");
        for (ArenaMap map : data.maps()) {
            text.append(Component.literal("\n " + map.name() + " ").withStyle(ChatFormatting.GOLD))
                    .append(modeStatus(map));
        }
        ctx.getSource().sendSuccess(() -> text, false);
        return data.maps().size();
    }

    /** Each listed mode, green if the map is ready for it, red if not. */
    private static MutableComponent modeStatus(ArenaMap map) {
        if (map.modes().isEmpty()) {
            return Component.literal("(no modes)").withStyle(ChatFormatting.GRAY);
        }
        MutableComponent text = Component.empty();
        for (ArenaMode mode : map.modes()) {
            text.append(Component.literal(mode.id() + " ").withStyle(map.hosts(mode) ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        return text;
    }

    private static int createMap(CommandContext<CommandSourceStack> ctx) {
        String name = ArenaData.key(StringArgumentType.getString(ctx, "name"));
        ArenaMap map = new ArenaMap(name, ctx.getSource().getLevel().dimension());
        if (!ArenaData.get(ctx.getSource().getServer()).add(map)) {
            ctx.getSource().sendFailure(Component.literal("A map named " + name + " already exists"));
            return 0;
        }
        success(ctx, "Created map " + name + " in " + map.dimension().location()
                + ". Next: /arena map " + name + " mode add <mode>, then spawns.");
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteMap(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ArenaMap map = map(ctx);
        ArenaData.get(ctx.getSource().getServer()).remove(map.name());
        success(ctx, "Deleted map " + map.name());
        return Command.SINGLE_SUCCESS;
    }

    private static int checkMap(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ArenaMap map = map(ctx);
        long free = map.spawns().stream().filter(spawn -> spawn.team().isEmpty() && spawn.side().isEmpty()).count();
        MutableComponent text = Component.literal("Map " + map.name()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" (" + map.dimension().location() + ")").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n Spawns: " + free + " free"));
        for (TeamColor team : TeamColor.values()) {
            text.append(Component.literal(", " + map.teamSpawns(team).size() + " " + team.id()).withStyle(team.color()));
        }
        for (SiegeSide side : SiegeSide.values()) {
            text.append(Component.literal(", " + map.siegeSpawns(side).size() + " " + side.id()));
        }
        text.append(Component.literal("\n Floor: " + map.floor().map(String::valueOf).orElse("none")));
        text.append(Component.literal("\n Core zone: " + map.core().map(box -> box.getXSpan() + "x" + box.getYSpan() + "x"
                + box.getZSpan() + " at " + box.minX() + " " + box.minY() + " " + box.minZ()).orElse("none")));
        if (map.modes().isEmpty()) {
            text.append(Component.literal("\n No modes yet: /arena map " + map.name() + " mode add <mode>").withStyle(ChatFormatting.RED));
        }
        for (ArenaMode mode : map.modes()) {
            List<String> missing = map.missing(mode);
            text.append(Component.literal("\n " + mode.displayName() + ": ")
                    .append(missing.isEmpty() ? Component.literal("ready").withStyle(ChatFormatting.GREEN)
                            : Component.literal("needs " + String.join(", ", missing)).withStyle(ChatFormatting.RED)));
        }
        ctx.getSource().sendSuccess(() -> text, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        ArenaMap map = map(ctx);
        String id = StringArgumentType.getString(ctx, "mode");
        ArenaMode mode = ArenaMode.byId(id).orElseThrow(() -> NO_MODE.create(id));
        boolean changed = add ? map.modes().add(mode) : map.modes().remove(mode);
        saved(ctx);
        success(ctx, (changed ? (add ? "Map " + map.name() + " now hosts " : "Map " + map.name() + " no longer hosts ")
                : "No change: map " + map.name() + (add ? " already hosts " : " didn't host ")) + mode.displayName());
        return changed ? Command.SINGLE_SUCCESS : 0;
    }

    private static int addSpawn(CommandContext<CommandSourceStack> ctx, Optional<TeamColor> team, Optional<SiegeSide> side)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ArenaMap map = mapHere(ctx);
        map.spawns().add(new ArenaMap.Spawn(Spot.of(player), team, side));
        saved(ctx);
        String kind = team.map(TeamColor::id).or(() -> side.map(SiegeSide::id)).map(id -> id + " ").orElse("");
        success(ctx, "Added " + (kind.startsWith("a") ? "an " : "a ") + kind + "spawn to " + map.name()
                + " (" + map.spawns().size() + " in all)");
        return Command.SINGLE_SUCCESS;
    }

    private static int clearSpawns(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ArenaMap map = map(ctx);
        int count = map.spawns().size();
        map.spawns().clear();
        saved(ctx);
        success(ctx, "Removed " + count + " spawn(s) from " + map.name());
        return count;
    }

    private static int setFloor(CommandContext<CommandSourceStack> ctx, Optional<Integer> floor) throws CommandSyntaxException {
        ArenaMap map = map(ctx);
        map.setFloor(floor);
        saved(ctx);
        success(ctx, floor.map(y -> "Players who fall below y " + y + " in " + map.name() + " are out")
                .orElse("Map " + map.name() + " has no floor height"));
        return Command.SINGLE_SUCCESS;
    }

    private static int setCore(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ArenaMap map = mapHere(ctx);
        BoundingBox box = BoundingBox.fromCorners(BlockPosArgument.getLoadedBlockPos(ctx, "from"), BlockPosArgument.getLoadedBlockPos(ctx, "to"));
        map.setCore(Optional.of(box));
        saved(ctx);
        success(ctx, "Set the core zone of " + map.name() + " (" + box.getXSpan() + "x" + box.getYSpan() + "x" + box.getZSpan()
                + " blocks; the core stands in the middle of its floor)");
        return Command.SINGLE_SUCCESS;
    }

    private static int clearCore(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ArenaMap map = map(ctx);
        map.setCore(Optional.empty());
        saved(ctx);
        success(ctx, "Removed the core zone of " + map.name());
        return Command.SINGLE_SUCCESS;
    }

    private static int teleportToMap(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ArenaMap map = map(ctx);
        ServerLevel level = ctx.getSource().getServer().getLevel(map.dimension());
        if (level == null || map.spawns().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Map " + map.name() + " has no spawns to go to"));
            return 0;
        }
        Spot spot = map.spawns().getFirst().spot();
        player.teleportTo(level, spot.pos().x, spot.pos().y, spot.pos().z, spot.yaw(), spot.pitch());
        return Command.SINGLE_SUCCESS;
    }
}
