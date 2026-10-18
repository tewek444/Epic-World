package me.nyxia.epicworld.client;

import me.nyxia.epicworld.config.EpicPerformance;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** The Epic World page in Sodium's video settings: the settings of config/epicworld-performance.toml. */
final class SodiumPage {
    private SodiumPage() {}

    private static final String MODID = me.nyxia.epicworld.EpicWorld.MODID;

    static void register(ConfigBuilder b) {
        String version = net.neoforged.fml.ModList.get().getModContainerById(MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        StorageEventHandler save = EpicPerformance.SPEC::save;

        var generation = b.createOptionGroup().setName(Component.translatable("epicworld.options.group.generation"))
                .addOption(bool(b, "fast_generation", EpicPerformance.FAST_GENERATION, save))
                .addOption(bool(b, "deferred_caves", EpicPerformance.DEFERRED_CAVES, save));

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        var voxy = b.createOptionGroup().setName(Component.translatable("epicworld.options.group.voxy"))
                .addOption(bool(b, "voxy_enabled", EpicPerformance.VOXY_ENABLED, save))
                .addOption(integer(b, "voxy_radius", EpicPerformance.VOXY_RADIUS, save, 16, 2048, 16,
                        v -> Component.translatable("epicworld.unit.chunks", v)))
                .addOption(shape(b, save))
                .addOption(integer(b, "voxy_threads", EpicPerformance.VOXY_THREADS, save, 0, cores, 1,
                        v -> v == 0 ? Component.translatable("epicworld.unit.auto") : Component.literal(Integer.toString(v))))
                .addOption(bool(b, "voxy_features", EpicPerformance.VOXY_FEATURES, save));

        b.registerModOptions(MODID, "Epic World", version)
                .addPage(b.createOptionPage().setName(Component.translatable("epicworld.options.page"))
                        .addOptionGroup(generation)
                        .addOptionGroup(voxy));
    }

    private static BooleanOptionBuilder bool(ConfigBuilder b, String id, ModConfigSpec.BooleanValue value, StorageEventHandler save) {
        return b.createBooleanOption(ResourceLocation.fromNamespaceAndPath(MODID, id))
                .setName(Component.translatable("epicworld.options." + id))
                .setTooltip(Component.translatable("epicworld.options." + id + ".tooltip"))
                .setImpact(OptionImpact.LOW)
                .setDefaultValue(value.getDefault())
                .setStorageHandler(save)
                .setBinding(value::set, () -> EpicPerformance.get(value));
    }

    private static IntegerOptionBuilder integer(ConfigBuilder b, String id, ModConfigSpec.IntValue value, StorageEventHandler save,
                                                int min, int max, int step,
                                                java.util.function.IntFunction<Component> format) {
        return b.createIntegerOption(ResourceLocation.fromNamespaceAndPath(MODID, id))
                .setName(Component.translatable("epicworld.options." + id))
                .setTooltip(Component.translatable("epicworld.options." + id + ".tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setRange(min, max, step)
                .setValueFormatter(format::apply)
                .setDefaultValue(Math.max(min, Math.min(max, value.getDefault())))
                .setStorageHandler(save)
                .setBinding(value::set, () -> {
                    int v = Math.max(min, Math.min(max, EpicPerformance.get(value)));
                    return v - (v - min) % step;
                });
    }

    private static EnumOptionBuilder<EpicPerformance.Shape> shape(ConfigBuilder b, StorageEventHandler save) {
        var value = EpicPerformance.VOXY_SHAPE;
        return b.createEnumOption(ResourceLocation.fromNamespaceAndPath(MODID, "voxy_shape"), EpicPerformance.Shape.class)
                .setName(Component.translatable("epicworld.options.voxy_shape"))
                .setTooltip(Component.translatable("epicworld.options.voxy_shape.tooltip"))
                .setImpact(OptionImpact.LOW)
                .setElementNameProvider(s -> Component.translatable(s == EpicPerformance.Shape.CIRCLE ? "epicworld.shape.circle" : "epicworld.shape.square"))
                .setDefaultValue(value.getDefault())
                .setStorageHandler(save)
                .setBinding(value::set, () -> EpicPerformance.get(value));
    }
}
