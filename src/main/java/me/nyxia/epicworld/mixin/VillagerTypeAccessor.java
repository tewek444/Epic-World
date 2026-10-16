package me.nyxia.epicworld.mixin;

import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VillagerType.class)
public interface VillagerTypeAccessor {
    @Accessor("BY_BIOME")
    static Map<ResourceKey<Biome>, VillagerType> epicworld$byBiome() {
        throw new AssertionError();
    }
}
