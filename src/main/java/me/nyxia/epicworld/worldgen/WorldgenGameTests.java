package me.nyxia.epicworld.worldgen;

import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.config.SettingsToml;
import me.nyxia.epicworld.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("epicworld")
@PrefixGameTestTemplate(false)
public final class WorldgenGameTests {
    private WorldgenGameTests() {}

    /** Generates chunks forwards, regenerates them in reverse order with a fresh state and compares every block. */
    @GameTest(template = "epicworld_empty", timeoutTicks = 1200)
    public static void generateAndReplayChunks(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var preset = registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(RuntimeWorldgen.PRESET);
        var generator = (EpicChunkGenerator) preset.overworld().orElseThrow().generator();
        var settingsHolder = registry.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(RuntimeWorldgen.SETTINGS);
        EpicSettings s = registry.registryOrThrow(ModRegistries.WORLD_SETTINGS).getOrThrow(ModRegistries.ACTIVE_SETTINGS);
        var state = RandomState.create(settingsHolder.value(), registry.lookupOrThrow(Registries.NOISE), 42);
        var bounds = LevelHeightAccessor.create(s.world().minY(), s.world().heightLimit() - s.world().minY());
        var biomes = registry.registryOrThrow(Registries.BIOME);
        var manager = helper.getLevel().structureManager();
        var positions = new ChunkPos[]{new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(-100, 171), new ChunkPos(2000, -3500)};
        ProtoChunk[] forward = new ProtoChunk[positions.length];
        for (int i = 0; i < positions.length; i++) {
            forward[i] = new ProtoChunk(positions[i], UpgradeData.EMPTY, bounds, biomes, null);
            await(helper, generator.createBiomes(state, Blender.empty(), manager, forward[i]));
            forward[i].setPersistedStatus(ChunkStatus.BIOMES);
            await(helper, generator.fillFromNoise(Blender.empty(), state, manager, forward[i]));
        }
        var replayState = RandomState.create(settingsHolder.value(), registry.lookupOrThrow(Registries.NOISE), 42);
        for (int i = positions.length - 1; i >= 0; i--) {
            var replay = new ProtoChunk(positions[i], UpgradeData.EMPTY, bounds, biomes, null);
            await(helper, generator.createBiomes(replayState, Blender.empty(), manager, replay));
            replay.setPersistedStatus(ChunkStatus.BIOMES);
            await(helper, generator.fillFromNoise(Blender.empty(), replayState, manager, replay));
            var pos = new BlockPos.MutableBlockPos();
            int solid = 0;
            for (int y = s.world().minY(); y < s.world().heightLimit(); y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.set(positions[i].getMinBlockX() + x, y, positions[i].getMinBlockZ() + z);
                        var block = forward[i].getBlockState(pos);
                        helper.assertTrue(block.equals(replay.getBlockState(pos)), "Chunk replay mismatch at " + pos);
                        if (!block.isAir()) solid++;
                        if (y >= s.world().height()) helper.assertTrue(block.isAir(), "Terrain exceeds upper bound at " + pos);
                    }
                }
            }
            helper.assertTrue(solid > 256, "Chunk must contain terrain");
            helper.assertTrue(forward[i].getNoiseBiome(positions[i].x * 4, s.world().seaLevel() / 4, positions[i].z * 4)
                    .unwrapKey().orElseThrow().location().getNamespace().equals("epicworld"), "Expected Epic World biome");
        }
        helper.succeed();
    }

    /**
     * Water must never sit next to open air at the same height (it would spill when touched): checked on a river,
     * a coast and an elevated lake, on the real interpolated blocks of a 3×3 chunk area.
     */
    @GameTest(template = "epicworld_empty", timeoutTicks = 2400)
    public static void waterIsContained(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var generator = (EpicChunkGenerator) registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(RuntimeWorldgen.PRESET)
                .overworld().orElseThrow().generator();
        var settingsHolder = registry.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(RuntimeWorldgen.SETTINGS);
        EpicSettings s = registry.registryOrThrow(ModRegistries.WORLD_SETTINGS).getOrThrow(ModRegistries.ACTIVE_SETTINGS);
        var state = RandomState.create(settingsHolder.value(), registry.lookupOrThrow(Registries.NOISE), 42);
        TerrainEngine engine = EpicChunkGenerator.engine(state);
        var targets = new java.util.ArrayList<BlockPos>();
        targets.add(engine.locate(BiomeCatalog.index("river"), 0, 0, 40000));
        targets.add(engine.locate(BiomeCatalog.index("beach"), 0, 0, 40000));
        found:
        for (int r = 0; r < 60000; r += 256) {
            for (int a = 0; a < 64; a++) {
                int x = (int) (Math.cos(a * Math.PI / 32) * r);
                int z = (int) (Math.sin(a * Math.PI / 32) * r);
                if (engine.column(x, z).lakeLevel != Integer.MIN_VALUE) {
                    targets.add(new BlockPos(x, 0, z));
                    break found;
                }
            }
        }
        helper.assertTrue(targets.size() == 3 && targets.stream().allMatch(java.util.Objects::nonNull),
                "Could not find a river, a beach and an elevated lake: " + targets);
        var bounds = LevelHeightAccessor.create(s.world().minY(), s.world().heightLimit() - s.world().minY());
        var biomes = registry.registryOrThrow(Registries.BIOME);
        var manager = helper.getLevel().structureManager();
        for (BlockPos target : targets) {
            var center = new ChunkPos(target);
            var chunks = new java.util.HashMap<Long, ProtoChunk>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    var pos = new ChunkPos(center.x + dx, center.z + dz);
                    var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, bounds, biomes, null);
                    await(helper, generator.createBiomes(state, Blender.empty(), manager, chunk));
                    chunk.setPersistedStatus(ChunkStatus.BIOMES);
                    await(helper, generator.fillFromNoise(Blender.empty(), state, manager, chunk));
                    chunks.put(pos.toLong(), chunk);
                }
            }
            java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> block =
                    p -> chunks.get(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4)).getBlockState(p);
            var pos = new BlockPos.MutableBlockPos();
            int waterBlocks = 0;
            for (int x = center.getMinBlockX(); x <= center.getMaxBlockX(); x++) {
                for (int z = center.getMinBlockZ(); z <= center.getMaxBlockZ(); z++) {
                    for (int y = s.world().seaLevel() - 40; y < s.world().height(); y++) {
                        pos.set(x, y, z);
                        if (!block.apply(pos).is(net.minecraft.world.level.block.Blocks.WATER)) continue;
                        waterBlocks++;
                        for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                            BlockPos n = pos.relative(side);
                            helper.assertTrue(!block.apply(n).isAir(), "Water at " + pos + " can spill into " + n);
                        }
                    }
                }
            }
            if (waterBlocks == 0) {
                for (var chunk : chunks.values()) {
                    for (int x = 0; x < 16 && waterBlocks == 0; x++) {
                        for (int z = 0; z < 16; z++) {
                            int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
                            if (chunk.getBlockState(new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z)))
                                    .is(net.minecraft.world.level.block.Blocks.WATER)) waterBlocks++;
                        }
                    }
                }
            }
            helper.assertTrue(waterBlocks > 0, "Expected water near " + target);
        }
        helper.succeed();
    }

    /** Diagnostic: real block profile across a coastline (logged; asserts nothing beyond generation working). */
    @GameTest(template = "epicworld_empty", timeoutTicks = 2400)
    public static void coastProfile(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var generator = (EpicChunkGenerator) registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(RuntimeWorldgen.PRESET)
                .overworld().orElseThrow().generator();
        var settingsHolder = registry.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(RuntimeWorldgen.SETTINGS);
        EpicSettings s = registry.registryOrThrow(ModRegistries.WORLD_SETTINGS).getOrThrow(ModRegistries.ACTIVE_SETTINGS);
        var state = RandomState.create(settingsHolder.value(), registry.lookupOrThrow(Registries.NOISE), 42);
        TerrainEngine engine = EpicChunkGenerator.engine(state);
        var bounds = LevelHeightAccessor.create(s.world().minY(), s.world().heightLimit() - s.world().minY());
        var biomes = registry.registryOrThrow(Registries.BIOME);
        var manager = helper.getLevel().structureManager();
        for (String name : new String[]{"beach", "mangrove_swamp", "swamp", "willow_swamp"}) {
            BlockPos beach = engine.locate(BiomeCatalog.index(name), 0, 0, 60000);
            if (beach == null) continue;
            int z = beach.getZ();
            var row = new StringBuilder();
            for (int cx = (beach.getX() >> 4) - 3; cx <= (beach.getX() >> 4) + 3; cx++) {
                var chunk = new ProtoChunk(new ChunkPos(cx, z >> 4), UpgradeData.EMPTY, bounds, biomes, null);
                await(helper, generator.createBiomes(state, Blender.empty(), manager, chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                await(helper, generator.fillFromNoise(Blender.empty(), state, manager, chunk));
                for (int dx = 0; dx < 16; dx++) {
                    int x = cx * 16 + dx;
                    int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, dx, z & 15);
                    var col = engine.column(x, z);
                    row.append(String.format(java.util.Locale.ROOT, "%d:%d/%s ", x, top - s.world().seaLevel(),
                            BiomeCatalog.get(col.biome).path().substring(0, 3)));
                }
            }
            me.nyxia.epicworld.EpicWorld.LOGGER.info("Coast profile {} z={}: {}", name, z, row);
        }
        helper.succeed();
    }

    /**
     * Budget check: biomes + noise (including Epic post-processing) for 25 fresh chunks on the server thread.
     * Logged for the README; fails only on a gross regression (more than 120 ms per chunk on average).
     */
    @GameTest(template = "epicworld_empty", timeoutTicks = 2400)
    public static void generationBudget(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var generator = (EpicChunkGenerator) registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(RuntimeWorldgen.PRESET)
                .overworld().orElseThrow().generator();
        var settingsHolder = registry.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(RuntimeWorldgen.SETTINGS);
        EpicSettings s = registry.registryOrThrow(ModRegistries.WORLD_SETTINGS).getOrThrow(ModRegistries.ACTIVE_SETTINGS);
        var state = RandomState.create(settingsHolder.value(), registry.lookupOrThrow(Registries.NOISE), 1234);
        var bounds = LevelHeightAccessor.create(s.world().minY(), s.world().heightLimit() - s.world().minY());
        var biomes = registry.registryOrThrow(Registries.BIOME);
        var manager = helper.getLevel().structureManager();
        long start = System.nanoTime();
        int count = 0;
        for (int cx = 0; cx < 5; cx++) {
            for (int cz = 0; cz < 5; cz++) {
                var chunk = new ProtoChunk(new ChunkPos(300 + cx * 7, -200 + cz * 7), UpgradeData.EMPTY, bounds, biomes, null);
                await(helper, generator.createBiomes(state, Blender.empty(), manager, chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                await(helper, generator.fillFromNoise(Blender.empty(), state, manager, chunk));
                count++;
            }
        }
        double perChunk = (System.nanoTime() - start) / 1e6 / count;
        me.nyxia.epicworld.EpicWorld.LOGGER.info("Epic World budget: biomes+noise {} ms per chunk (single thread)",
                String.format(java.util.Locale.ROOT, "%.1f", perChunk));
        helper.assertTrue(perChunk < 120, "Generation too slow: " + perChunk + " ms per chunk");
        helper.succeed();
    }

    /**
     * Deferred depth: filling the top and the deep part of a chunk separately must give exactly the blocks and biomes of
     * one vanilla fill. Logs how biome and noise time split between the two parts (after a warm-up round).
     */
    @GameTest(template = "epicworld_empty", timeoutTicks = 6000)
    public static void deferredSplitMatchesFullFill(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var generator = (EpicChunkGenerator) registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(RuntimeWorldgen.PRESET)
                .overworld().orElseThrow().generator();
        var settingsHolder = registry.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(RuntimeWorldgen.SETTINGS);
        var settings = settingsHolder.value();
        EpicSettings s = registry.registryOrThrow(ModRegistries.WORLD_SETTINGS).getOrThrow(ModRegistries.ACTIVE_SETTINGS);
        var state = RandomState.create(settings, registry.lookupOrThrow(Registries.NOISE), 1234);
        var engine = EpicChunkGenerator.engine(state);
        var bounds = LevelHeightAccessor.create(s.world().minY(), s.world().heightLimit() - s.world().minY());
        var biomes = registry.registryOrThrow(Registries.BIOME);
        var manager = helper.getLevel().structureManager();
        int noiseMin = settings.noiseSettings().minY();
        int noiseTop = noiseMin + settings.noiseSettings().height();
        var fluids = me.nyxia.epicworld.worldgen.deferred.DeepNoise.fluids(settings);
        // full biomes (cold engine), full fill, biomes top, biomes deep, noise top, noise deep
        long[] time = new long[6];
        long deepBlocks = 0;
        long allBlocks = 0;
        int count = 0;
        for (int round = 0; round < 2; round++) {
            for (int cx = 0; cx < 5; cx++) {
                for (int cz = 0; cz < 5; cz++) {
                    // Round 0 warms up the JIT on other chunks and is not counted.
                    var pos = round == 0 ? new ChunkPos(-900 + cx * 9, 700 + cz * 9) : new ChunkPos(-2000 + cx * 173 + (cz & 1) * 11, 1500 + cz * 131);
                    long[] t = new long[6];
                    var full = new ProtoChunk(pos, UpgradeData.EMPTY, bounds, biomes, null);
                    long t0 = System.nanoTime();
                    await(helper, generator.createBiomes(state, Blender.empty(), manager, full));
                    t[0] = System.nanoTime() - t0;
                    full.setPersistedStatus(ChunkStatus.BIOMES);
                    t0 = System.nanoTime();
                    me.nyxia.epicworld.worldgen.deferred.DeferredDepth.BYPASS.set(true);
                    try {
                        await(helper, generator.fillFromNoise(Blender.empty(), state, manager, full));
                    } finally {
                        me.nyxia.epicworld.worldgen.deferred.DeferredDepth.BYPASS.set(false);
                    }
                    t[1] = System.nanoTime() - t0;

                    int top = me.nyxia.epicworld.worldgen.deferred.DeferredDepth.deferTop(engine, pos);
                    int split = top == Integer.MIN_VALUE ? noiseMin : top;
                    var parts = new ProtoChunk(pos, UpgradeData.EMPTY, bounds, biomes, null);
                    await(helper, java.util.concurrent.CompletableFuture.runAsync(() -> {
                        var source = generator.getBiomeSource();
                        long a = System.nanoTime();
                        me.nyxia.epicworld.worldgen.deferred.DeepNoise.fillBiomes(parts, source, state.sampler(), split,
                                bounds.getMaxBuildHeight());
                        long b = System.nanoTime();
                        me.nyxia.epicworld.worldgen.deferred.DeepNoise.fillBiomes(parts, source, state.sampler(),
                                bounds.getMinBuildHeight(), split);
                        long c = System.nanoTime();
                        var beard = net.minecraft.world.level.levelgen.Beardifier.forStructuresInChunk(manager, pos);
                        long d = System.nanoTime();
                        var upper = me.nyxia.epicworld.worldgen.deferred.DeepNoise.noiseChunk(parts, state, settings, beard, fluids,
                                Blender.empty(), split, noiseTop);
                        me.nyxia.epicworld.worldgen.deferred.DeepNoise.fill(parts, upper, settings, split, noiseTop);
                        long e = System.nanoTime();
                        if (split > noiseMin) {
                            var deep = me.nyxia.epicworld.worldgen.deferred.DeepNoise.noiseChunk(parts, state, settings, beard, fluids,
                                    Blender.empty(), noiseMin, split);
                            me.nyxia.epicworld.worldgen.deferred.DeepNoise.fill(parts, deep, settings, noiseMin, split);
                        }
                        t[2] = b - a;
                        t[3] = c - b;
                        t[4] = e - d;
                        t[5] = System.nanoTime() - e;
                    }, net.minecraft.Util.backgroundExecutor()));
                    parts.setPersistedStatus(ChunkStatus.BIOMES);
                    EpicChunkGenerator.fillLakes(engine, parts, null);
                    var p = new BlockPos.MutableBlockPos();
                    for (int y = noiseMin; y < noiseTop; y++) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                p.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                                var a = full.getBlockState(p);
                                var b = parts.getBlockState(p);
                                if (a != b) {
                                    helper.fail("Split fill (at " + split + ") differs at " + p.toShortString() + ": " + a + " vs " + b);
                                    return;
                                }
                                int qx = (pos.getMinBlockX() + x) >> 2;
                                int qy = y >> 2;
                                int qz = (pos.getMinBlockZ() + z) >> 2;
                                if ((y & 3) == 0 && (x & 3) == 0 && (z & 3) == 0
                                        && !full.getNoiseBiome(qx, qy, qz).equals(parts.getNoiseBiome(qx, qy, qz))) {
                                    helper.fail("Split biomes differ at " + p.toShortString());
                                    return;
                                }
                            }
                        }
                    }
                    if (round == 0) continue;
                    for (int i = 0; i < 6; i++) time[i] += t[i];
                    deepBlocks += 256L * (split - noiseMin);
                    allBlocks += 256L * (noiseTop - noiseMin);
                    count++;
                }
            }
        }
        double ms = 1e6 * count;
        me.nyxia.epicworld.EpicWorld.LOGGER.info(String.format(java.util.Locale.ROOT,
                "Epic World deferred split (noise %d..%d, build limit %d): full biomes %.1f ms (cold engine), full fill %.1f ms;"
                        + " split: biomes top %.1f + deep %.1f ms, noise top %.1f + deep %.1f ms per chunk;"
                        + " deep part is %.0f%% of the noise blocks",
                noiseMin, noiseTop, bounds.getMaxBuildHeight(), time[0] / ms, time[1] / ms, time[2] / ms, time[3] / ms,
                time[4] / ms, time[5] / ms, 100.0 * deepBlocks / allBlocks));
        helper.succeed();
    }

    /** Every biome the Epic generator can place must have a consistent feature order (no "feature order cycle"). */
    @GameTest(template = "epicworld_empty")
    public static void featureOrderIsConsistent(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var generator = registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(RuntimeWorldgen.PRESET)
                .overworld().orElseThrow().generator();
        var biomes = java.util.List.copyOf(generator.getBiomeSource().possibleBiomes());
        helper.assertTrue(biomes.size() == BiomeCatalog.ENTRIES.size(), "Biome source must list every catalog biome");
        net.minecraft.world.level.biome.FeatureSorter.buildFeaturesPerStep(biomes,
                biome -> biome.value().getGenerationSettings().features(), true);
        for (var biome : biomes) {
            for (var category : net.minecraft.world.entity.MobCategory.values()) {
                for (var spawn : biome.value().getMobSettings().getMobs(category).unwrap()) {
                    var own = spawn.type.getCategory();
                    helper.assertTrue(own == category || own == net.minecraft.world.entity.MobCategory.MISC,
                            spawn.type + " spawns under " + category + " in " + biome.getRegisteredName());
                }
            }
        }
        helper.succeed();
    }

    /** Snapshot files must reproduce exactly the settings they were written from. */
    @GameTest(template = "epicworld_empty")
    public static void settingsRoundTrip(GameTestHelper helper) {
        var custom = new EpicSettings(new EpicSettings.World(-128, 512, 1280, 160),
                new EpicSettings.Biomes(2.5, java.util.Map.of("epicworld:plains", new EpicSettings.BiomeOverride(
                        java.util.Optional.of(false), java.util.Optional.of(1.5), java.util.Optional.of(7)))),
                new EpicSettings.Rivers(true, 24, 128, 0.1, false, false, false, false),
                new EpicSettings.Lakes(false, 40, 300, 100, false, false, false),
                new EpicSettings.Oceans(true, 0.35, false, false, 180, false),
                new EpicSettings.Archipelagos(false, 20, 900), new EpicSettings.Atolls(false, 80, 900, false, false),
                new EpicSettings.Compatibility(false, 64)).validate();
        for (EpicSettings settings : new EpicSettings[]{EpicSettings.DEFAULT, custom}) {
            String text = SettingsToml.write(settings, true);
            var parsed = SettingsToml.parse(text, warning -> helper.fail("Unexpected warning: " + warning));
            helper.assertTrue(parsed.equals(settings), "TOML round trip changed settings: " + parsed);
        }
        helper.assertTrue(SettingsToml.warningsOf("[world]\nmin_y = -64\nbogus = 1\n").size() == 1, "Unknown keys must be reported");
        try {
            SettingsToml.parse("[world]\nmin_y = -60\n", w -> {});
            helper.fail("min_y that is not a multiple of 16 must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        helper.succeed();
    }

    /** Terrain stays inside the vertical bounds and the engine is order independent, across settings. */
    @GameTest(template = "epicworld_empty", timeoutTicks = 1200)
    public static void engineBoundsAndDeterminism(GameTestHelper helper) {
        for (long seed : new long[]{1, 42, -77}) {
            var engine = TerrainEngine.get(seed, EpicSettings.DEFAULT);
            double[] heights = new double[4096];
            for (int i = 0; i < heights.length; i++) {
                int x = (i % 64) * 1531 - 50000;
                int z = (i / 64) * 1777 - 50000;
                var column = engine.column(x, z);
                heights[i] = column.height;
                helper.assertTrue(column.height > engine.minY + 8 && column.height < engine.top, "Out of bounds at " + x + "," + z);
                helper.assertTrue(engine.biomes.enabled[column.biome] || column.biome == BiomeCatalog.index("plains"),
                        "Disabled biome generated at " + x + "," + z);
            }
            // A fresh engine (no caches), queried in reverse, must agree bit for bit.
            var fresh = new java.util.concurrent.atomic.AtomicReference<TerrainEngine>();
            Thread t = new Thread(() -> fresh.set(TerrainEngine.get(seed, EpicSettings.DEFAULT)));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            double[] reverse = new double[heights.length];
            Thread reader = new Thread(() -> {
                for (int i = heights.length - 1; i >= 0; i--) {
                    reverse[i] = fresh.get().column((i % 64) * 1531 - 50000, (i / 64) * 1777 - 50000).height;
                }
            });
            reader.start();
            try {
                reader.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            for (int i = 0; i < heights.length; i++) {
                helper.assertTrue(Double.doubleToLongBits(heights[i]) == Double.doubleToLongBits(reverse[i]), "Order dependent terrain");
            }
        }
        helper.succeed();
    }

    private static void await(GameTestHelper helper, java.util.concurrent.CompletableFuture<?> future) {
        // Noise generation asks the test level for structure-reference chunks, which only the server thread can
        // provide. MinecraftServer#managedBlock stops polling chunk tasks once the tick is over budget, so pump the
        // chunk source explicitly instead of a plain join.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        var server = helper.getLevel().getServer();
        while (!future.isDone() && System.nanoTime() < deadline) {
            boolean worked = helper.getLevel().getChunkSource().pollTask();
            worked |= server.pollTask();
            if (!worked) java.util.concurrent.locks.LockSupport.parkNanos(200_000);
        }
        helper.assertTrue(future.isDone(), "World generation exceeded 60 seconds");
        future.join();
    }

}
