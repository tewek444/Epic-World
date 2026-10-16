package me.nyxia.epicworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.registry.ModRegistries;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Stored in every chunk Epic World generates: which generator version and settings produced its terrain. */
public record GenerationStamp(int version, int fingerprint) {
    public static final GenerationStamp NONE = new GenerationStamp(0, 0);

    public static final Codec<GenerationStamp> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("version").forGetter(GenerationStamp::version),
            Codec.INT.fieldOf("fingerprint").forGetter(GenerationStamp::fingerprint)).apply(i, GenerationStamp::new));

    /** Hash of everything that shapes terrain. The compatibility section only affects transitions and is excluded. */
    public static int fingerprint(EpicSettings s) {
        return Objects.hash(EpicSettings.GENERATOR_VERSION, s.world(), s.biomes(), s.rivers(), s.lakes(), s.oceans(),
                s.archipelagos(), s.atolls());
    }

    public static GenerationStamp current(EpicSettings settings) {
        return new GenerationStamp(EpicSettings.GENERATOR_VERSION, fingerprint(settings));
    }

    public static void mark(ChunkAccess chunk, TerrainEngine engine) {
        chunk.setData(ModRegistries.GENERATION_STAMP, current(engine.settings));
    }

    public static GenerationStamp of(ChunkAccess chunk) {
        return chunk.hasData(ModRegistries.GENERATION_STAMP) ? chunk.getData(ModRegistries.GENERATION_STAMP) : NONE;
    }
}
