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
 */
public final class EpicCommands {
    private EpicCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("epicworld").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(EpicCommands::info))
                .then(Commands.literal("benchmark").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 32))
                        .executes(c -> me.nyxia.epicworld.dev.GenerationBenchmark.start(c.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("locate").then(Commands.argument("biome", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(BiomeCatalog.paths(), b))
                        .executes(EpicCommands::locate))));
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
        int distance = (int) Math.sqrt(found.distSqr(new BlockPos(origin.getX(), found.getY(), origin.getZ())));
        String tp = "/tp @s " + found.getX() + " " + found.getY() + " " + found.getZ();
        source.sendSuccess(() -> Component.literal("Nearest " + name + ": ").append(Component.literal(
                "[" + found.getX() + ", " + found.getY() + ", " + found.getZ() + "]").withStyle(Style.EMPTY
                .withColor(net.minecraft.ChatFormatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(tp)))))
                .append(Component.literal(" (" + distance + " blocks away)")), false);
        return 1;
    }
}
