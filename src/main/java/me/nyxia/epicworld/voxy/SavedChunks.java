package me.nyxia.epicworld.voxy;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * The block sections of a chunk as saved in the world (with player builds), read with the same codecs as
 * {@code ChunkSerializer}, for LODs of chunks that exist. Only full chunks count: anything else is generated.
 */
final class SavedChunks {
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private final ServerLevel level;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomes;
    private final Holder<Biome> plains;

    SavedChunks(ServerLevel level) {
        this.level = level;
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.plains = registry.getHolderOrThrow(Biomes.PLAINS);
        this.biomes = PalettedContainer.codecRO(registry.asHolderIdMap(), registry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, plains);
    }

    /** The saved tag of a chunk (updated by the data fixer), or empty when the chunk was never saved. */
    Optional<CompoundTag> read(ChunkPos pos) {
        try {
            return level.getChunkSource().chunkMap.readChunk(pos).join();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The non-empty sections of a saved full chunk (skipping a deferred placeholder depth), or null if it is not full. */
    @Nullable
    List<LodPayloads.Section> sections(CompoundTag tag) {
        if (!tag.getString("Status").replace("minecraft:", "").equals("full")) return null;
        int floor = Integer.MIN_VALUE;
        CompoundTag attachments = tag.getCompound("neoforge:attachments");
        CompoundTag defer = attachments.getCompound("epicworld:defer");
        if (defer.contains("top") && defer.getInt("stage") == 0) floor = defer.getInt("top");
        ListTag list = tag.getList("sections", Tag.TAG_COMPOUND);
        var out = new ArrayList<LodPayloads.Section>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            int y = section.getByte("Y");
            if (y < level.getMinSection() || y >= level.getMaxSection()) continue;
            if (floor != Integer.MIN_VALUE && (y + 1) * 16 <= floor - 16) continue;
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) continue;
            var states = STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")).result().orElse(null);
            if (states == null) continue;
            PalettedContainerRO<Holder<Biome>> biome = section.contains("biomes", Tag.TAG_COMPOUND)
                    ? biomes.parse(NbtOps.INSTANCE, section.getCompound("biomes")).result().orElse(null) : null;
            if (biome == null) {
                biome = new PalettedContainer<>(level.registryAccess().registryOrThrow(Registries.BIOME).asHolderIdMap(), plains,
                        PalettedContainer.Strategy.SECTION_BIOMES);
            }
            var built = new LevelChunkSection(states, biome);
            if (built.hasOnlyAir()) continue;
            out.add(new LodPayloads.Section(y, built));
        }
        return out;
    }
}
