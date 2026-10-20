package me.nyxia.epicworld.worldgen;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import me.nyxia.epicworld.data.WorldSettingsStore;
import me.nyxia.epicworld.registry.ModRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /epicworld info} — what the generator knows about the current column and where the settings live.
 * {@code /epicworld locate <biome>} — nearest surface or cave biome region, searched far beyond vanilla /locate.
 * {@code /epicworld locate tunnel <plain|glowing|glowworms>} — nearest river tunnel through a mountain.
 */
public final class EpicCommands {
    private EpicCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("epicworld").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(EpicCommands::info))
                .then(Commands.literal("gen")
                        .executes(c -> genOpen(c.getSource()))
                        .then(Commands.literal("start")
                                .then(Commands.argument("x1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("x2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(c -> genStart(c, false))
                                        .then(Commands.literal("circle").executes(c -> genStart(c, true)))
                                        .then(Commands.literal("square").executes(c -> genStart(c, false))))))))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(16, 1_000_000))
                                        .executes(c -> genRadius(c, false))
                                        .then(Commands.literal("circle").executes(c -> genRadius(c, true)))
                                        .then(Commands.literal("square").executes(c -> genRadius(c, false)))))
                        .then(Commands.literal("pause").executes(c -> genControl(c.getSource(), me.nyxia.epicworld.network.EpicNetwork.PregenAction.PAUSE)))
                        .then(Commands.literal("resume").executes(c -> genControl(c.getSource(), me.nyxia.epicworld.network.EpicNetwork.PregenAction.RESUME)))
                        .then(Commands.literal("cancel").executes(c -> genControl(c.getSource(), me.nyxia.epicworld.network.EpicNetwork.PregenAction.CANCEL)))
                        .then(Commands.literal("status").executes(c -> genStatus(c.getSource()))))
                .then(Commands.literal("voxy")
                        .executes(c -> voxyStatus(c.getSource()))
                        .then(Commands.literal("reset").executes(c -> voxyReset(c.getSource(), null))
                                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                        .executes(c -> voxyReset(c.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"))))))
                .then(Commands.literal("deferred")
                        .executes(c -> deferredStatus(c.getSource()))
                        .then(Commands.literal("fill").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 16))
                                .executes(c -> deferredFill(c.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radius"))))))
                .then(Commands.literal("benchmark").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 32))
                        .executes(c -> me.nyxia.epicworld.dev.GenerationBenchmark.start(c.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("locate").then(Commands.literal("tunnel")
                                .then(Commands.literal("plain").executes(c -> locateTunnel(c, TerrainEngine.TUNNEL_PLAIN, "plain")))
                                .then(Commands.literal("glowing").executes(c -> locateTunnel(c, TerrainEngine.TUNNEL_GLOWING, "glowing")))
                                .then(Commands.literal("glowworms").executes(c -> locateTunnel(c, TerrainEngine.TUNNEL_GLOWWORMS, "glowworms"))))
                        .then(Commands.argument("biome", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(BiomeCatalog.paths(), b))
                        .executes(EpicCommands::locate))));
    }

    private static int genOpen(CommandSourceStack source) {
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            me.nyxia.epicworld.network.EpicNetwork.openScreen(player);
        } else {
            source.sendSuccess(() -> Component.translatable("epicworld.pregen.console"), false);
        }
        return 1;
    }

    private static int genStart(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, boolean circle) {
        int x1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x1");
        int z1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z1");
        int x2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x2");
        int z2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z2");
        return genBegin(c.getSource(), x1, z1, x2, z2, circle);
    }

    private static int genRadius(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, boolean circle) {
        int r = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radius");
        var at = net.minecraft.core.BlockPos.containing(c.getSource().getPosition());
        return genBegin(c.getSource(), at.getX() - r, at.getZ() - r, at.getX() + r, at.getZ() + r, circle);
    }

    private static int genBegin(CommandSourceStack source, int x1, int z1, int x2, int z2, boolean circle) {
        var player = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? p : null;
        Component started = me.nyxia.epicworld.pregen.Pregenerator.start(source.getLevel(), x1, z1, x2, z2, circle, player);
        if (started == null) {
            source.sendFailure(Component.translatable("epicworld.pregen.busy"));
            return 0;
        }
        source.sendSuccess(() -> started, true);
        return 1;
    }

    private static int genControl(CommandSourceStack source, int action) {
        var task = me.nyxia.epicworld.pregen.Pregenerator.task();
        if (task == null) {
            source.sendFailure(Component.translatable("epicworld.pregen.none"));
            return 0;
        }
        Component msg;
        if (action == me.nyxia.epicworld.network.EpicNetwork.PregenAction.CANCEL) {
            long done = task.done();
            task.cancel();
            msg = Component.translatable("epicworld.pregen.cancelled", done);
        } else {
            boolean pause = action == me.nyxia.epicworld.network.EpicNetwork.PregenAction.PAUSE;
            task.pause(pause);
            msg = Component.translatable(pause ? "epicworld.pregen.paused" : "epicworld.pregen.resumed");
        }
        source.sendSuccess(() -> msg, true);
        return 1;
    }

    private static int genStatus(CommandSourceStack source) {
        var task = me.nyxia.epicworld.pregen.Pregenerator.task();
        if (task == null) {
            source.sendSuccess(() -> Component.translatable("epicworld.pregen.none"), false);
        } else {
            Component progress = task.progress();
            source.sendSuccess(() -> task.paused() ? progress.copy().append(Component.translatable("epicworld.debug.pregen.paused")) : progress, false);
        }
        return 1;
    }

    private static int voxyStatus(CommandSourceStack source) {
        var g = me.nyxia.epicworld.voxy.LodGenerator.active();
        String blocker = me.nyxia.epicworld.voxy.LodGenerator.blocker();
        String text = g != null ? g.status() : "не работает: " + (blocker != null ? Component.translatable(blocker).getString()
                : "не мир Epic World или выделенный сервер");
        source.sendSuccess(() -> Component.literal("Лоды Voxy: " + text), false);
        return 1;
    }

    private static int voxyReset(CommandSourceStack source, @javax.annotation.Nullable net.minecraft.server.level.ServerPlayer player) {
        var g = me.nyxia.epicworld.voxy.LodGenerator.active();
        if (g == null) {
            source.sendFailure(Component.literal("Лоды Voxy не работают в этом мире."));
            return 0;
        }
        g.reset(player == null ? null : player.getUUID());
        source.sendSuccess(() -> Component.literal(player == null
                ? "Список отправленных лодов очищен у всех игроков, они будут отправлены заново."
                : "Список отправленных лодов игрока " + player.getName().getString() + " очищен."), true);
        return 1;
    }

    private static int deferredStatus(CommandSourceStack source) {
        var pos = new net.minecraft.world.level.ChunkPos(net.minecraft.core.BlockPos.containing(source.getPosition()));
        String text = me.nyxia.epicworld.worldgen.deferred.DeepFiller.status(source.getLevel(), pos, 8);
        source.sendSuccess(() -> Component.literal("Deferred depth (experiment), " + text), false);
        return 1;
    }

    private static int deferredFill(CommandSourceStack source, int radius) {
        var pos = new net.minecraft.world.level.ChunkPos(net.minecraft.core.BlockPos.containing(source.getPosition()));
        int n = me.nyxia.epicworld.worldgen.deferred.DeepFiller.force(source.getLevel(), pos, radius);
        source.sendSuccess(() -> Component.literal("Completing the depth of " + n + " chunks"), false);
        return n;
    }

    private static TerrainEngine engine(CommandSourceStack source) {
        TerrainEngine engine = EpicChunkGenerator.engine(source.getServer().overworld().getChunkSource().randomState());
        if (engine == null) source.sendFailure(Component.literal("This world does not use Epic World generation."));
        return engine;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        TerrainEngine engine = engine(source);
        if (engine == null) return 0;
        BlockPos pos = BlockPos.containing(source.getPosition());
        var column = engine.column(pos.getX(), pos.getZ());
        var settings = source.getServer().overworld().registryAccess().registryOrThrow(ModRegistries.WORLD_SETTINGS)
                .get(ModRegistries.ACTIVE_SETTINGS);
        var snapshot = source.getServer().getWorldPath(LevelResource.ROOT).resolve(WorldSettingsStore.SNAPSHOT).normalize();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Epic World: surface %.1f, sea %d, land %.2f, mountain %.2f, temperature %.2f, humidity %.2f\n"
                        + "biome %s, cave biome %s, river %s, elevated lake %s\n"
                        + "settings fingerprint %08x, snapshot %s",
                column.height, engine.sea, column.land, column.mountain, column.temperature, column.humidity,
                BiomeCatalog.get(column.biome).id(), column.caveBiome < 0 ? "-" : BiomeCatalog.get(column.caveBiome).id(),
                column.river, column.lakeLevel == Integer.MIN_VALUE ? "-" : Integer.toString(column.lakeLevel),
                settings == null ? 0 : GenerationStamp.fingerprint(settings), snapshot)), false);
        return 1;
    }

    private static int locateTunnel(CommandContext<CommandSourceStack> context, int kind, String name) {
        var source = context.getSource();
        TerrainEngine engine = engine(source);
        if (engine == null) return 0;
        BlockPos origin = BlockPos.containing(source.getPosition());
        BlockPos found = engine.locateTunnel(kind, origin.getX(), origin.getZ(), 16_000);
        if (found == null) {
            source.sendFailure(Component.literal("No " + name + " river tunnel within 16000 blocks"));
            return 0;
        }
        report(source, name + " river tunnel", origin, found);
        return 1;
    }

    private static void report(CommandSourceStack source, String what, BlockPos origin, BlockPos found) {
        int distance = (int) Math.sqrt(found.distSqr(new BlockPos(origin.getX(), found.getY(), origin.getZ())));
        String tp = "/tp @s " + found.getX() + " " + found.getY() + " " + found.getZ();
        source.sendSuccess(() -> Component.literal("Nearest " + what + ": ").append(Component.literal(
                "[" + found.getX() + ", " + found.getY() + ", " + found.getZ() + "]").withStyle(Style.EMPTY
                .withColor(net.minecraft.ChatFormatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(tp)))))
                .append(Component.literal(" (" + distance + " blocks away)")), false);
    }

    private static int locate(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        TerrainEngine engine = engine(source);
        if (engine == null) return 0;
        String name = StringArgumentType.getString(context, "biome").replace("epicworld:", "");
        int target;
        try {
            target = BiomeCatalog.index(name);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Unknown Epic World biome " + name));
            return 0;
        }
        BlockPos origin = BlockPos.containing(source.getPosition());
        BlockPos found = engine.locate(target, origin.getX(), origin.getZ(), 120_000);
        if (found == null) {
            source.sendFailure(Component.literal("No " + name + " within 120000 blocks"));
            return 0;
        }
        report(source, name, origin, found);
        return 1;
    }
}
