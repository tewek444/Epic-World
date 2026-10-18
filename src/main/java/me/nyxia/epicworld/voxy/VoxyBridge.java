package me.nyxia.epicworld.voxy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nullable;
import me.nyxia.epicworld.EpicWorld;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Voxy (a Fabric mod, here through Sinytra Connector) is an optional dependency: its ingest entry point is looked up by
 * reflection, so Epic World loads and runs without it, and a changed Voxy only disables the integration.
 * {@code VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection, chunkX, sectionY, chunkZ, blockLight, skyLight)}
 * queues one section to be turned into LODs.
 */
public final class VoxyBridge {
    private VoxyBridge() {}

    private static volatile boolean initialized;
    @Nullable private static MethodHandle identifierOf;
    @Nullable private static MethodHandle rawIngest;
    private static final DataLayer FULL_SKY = new DataLayer(15);

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            ClassLoader loader = VoxyBridge.class.getClassLoader();
            Class<?> service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService", true, loader);
            Class<?> identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", true, loader);
            var lookup = MethodHandles.publicLookup();
            identifierOf = lookup.findStatic(identifier, "of", MethodType.methodType(identifier, Level.class));
            rawIngest = lookup.findStatic(service, "rawIngest", MethodType.methodType(boolean.class, identifier,
                    LevelChunkSection.class, int.class, int.class, int.class, DataLayer.class, DataLayer.class));
            EpicWorld.LOGGER.info("Epic World: Voxy found, LOD generation available");
        } catch (ClassNotFoundException e) {
            identifierOf = null;
            rawIngest = null;
        } catch (Throwable e) {
            identifierOf = null;
            rawIngest = null;
            EpicWorld.LOGGER.warn("Epic World: Voxy is installed but its ingest API was not found (another version?); LOD generation is off", e);
        }
    }

    public static boolean available() {
        if (!initialized) init();
        return rawIngest != null;
    }

    /** The Voxy world of a level, or null when Voxy has none (yet). */
    @Nullable
    public static Object world(Level level) {
        if (!available()) return null;
        try {
            return identifierOf.invoke(level);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Queues the sections of a whole chunk (skipping air and a deferred placeholder depth below its first section).
     */
    public static void ingestChunk(Level level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        Object world = world(level);
        if (world == null) return;
        int floor = me.nyxia.epicworld.worldgen.deferred.DeferredDepth.placeholderTop(chunk);
        var pos = chunk.getPos();
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            var section = chunk.getSection(i);
            if (section.hasOnlyAir()) continue;
            int sectionY = chunk.getSectionYFromSectionIndex(i);
            if (floor != Integer.MIN_VALUE && (sectionY + 1) * 16 <= floor - 16) continue;
            ingest(world, section, pos.x, sectionY, pos.z);
        }
    }

    /** Queues one section (absolute section Y) for LOD building; false if Voxy refused it. */
    public static boolean ingest(Object world, LevelChunkSection section, int chunkX, int sectionY, int chunkZ) {
        try {
            return (boolean) rawIngest.invoke(world, section, chunkX, sectionY, chunkZ, (DataLayer) null, FULL_SKY);
        } catch (Throwable e) {
            return false;
        }
    }
}
