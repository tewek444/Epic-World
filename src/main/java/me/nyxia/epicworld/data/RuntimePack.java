package me.nyxia.epicworld.data;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.worldgen.RuntimeWorldgen;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Always-enabled data pack whose worldgen files are generated from the settings of the world being loaded.
 */
public final class RuntimePack implements PackResources {
    public static final String ID = "epicworld_generation";
    /** Packs with known-pack info load as "stable" registry data, so Minecraft does not warn about experimental settings. */
    public static final KnownPack KNOWN = new KnownPack(EpicWorld.MODID, "generation", "1");
    private static final Map<EpicSettings, Map<String, byte[]>> CACHE = new ConcurrentHashMap<>();

    private final PackLocationInfo location;
    @Nullable
    private volatile Map<String, byte[]> files;

    private RuntimePack(PackLocationInfo location) {
        this.location = location;
    }

    public static void register(AddPackFindersEvent event) {
        // Not added to the "trusted" repository: clients never claim to know this pack, so servers always send its
        // (world-specific) registry contents.
        if (event.getPackType() != PackType.SERVER_DATA || event.isTrusted()) return;
        var info = new PackLocationInfo(ID, Component.literal("Epic World generation"), PackSource.BUILT_IN,
                Optional.of(KNOWN));
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) { return new RuntimePack(location); }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) { return new RuntimePack(location); }
        };
        Pack pack = Pack.readMetaAndCreate(info, supplier, PackType.SERVER_DATA, new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) event.addRepositorySource(consumer -> consumer.accept(pack));
    }

    private Map<String, byte[]> files() {
        Map<String, byte[]> result = files;
        if (result == null) {
            synchronized (this) {
                result = files;
                if (result == null) {
                    EpicSettings settings = WorldSettingsStore.current();
                    if (CACHE.size() > 4) CACHE.clear();
                    result = CACHE.computeIfAbsent(settings, s -> {
                        long start = System.nanoTime();
                        var generated = RuntimeWorldgen.generate(s);
                        EpicWorld.LOGGER.info("Epic World: generated {} worldgen files in {} ms", generated.size(),
                                (System.nanoTime() - start) / 1_000_000);
                        return generated;
                    });
                    files = result;
                }
            }
        }
        return result;
    }

    private static final byte[] PACK_META = ("{\"pack\":{\"description\":\"Epic World generation (from settings)\",\"pack_format\":"
            + SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA) + "}}").getBytes(StandardCharsets.UTF_8);

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        if (elements.length == 1 && elements[0].equals("pack.mcmeta")) return () -> new ByteArrayInputStream(PACK_META);
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.SERVER_DATA) return null;
        byte[] data = files().get("data/" + location.getNamespace() + "/" + location.getPath());
        return data == null ? null : () -> new ByteArrayInputStream(data);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.SERVER_DATA) return;
        String prefix = "data/" + namespace + "/" + path + "/";
        for (var entry : files().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String resourcePath = entry.getKey().substring(("data/" + namespace + "/").length());
            byte[] data = entry.getValue();
            output.accept(ResourceLocation.fromNamespaceAndPath(namespace, resourcePath), () -> new ByteArrayInputStream(data));
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? Set.of(EpicWorld.MODID) : Set.of();
    }

    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
        return AbstractPackResources.getMetadataFromStream(deserializer, new ByteArrayInputStream(PACK_META));
    }

    @Override
    public PackLocationInfo location() {
        return location;
    }

    @Override
    public void close() {}
}
