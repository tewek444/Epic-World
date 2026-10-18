package me.nyxia.epicworld.block;

import java.util.function.Supplier;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;

/** The willow boat type, added to {@link Boat.Type} through {@code META-INF/enumextensions.json}. */
public final class ModBoats {
    private ModBoats() {}

    public static final EnumProxy<Boat.Type> WILLOW = new EnumProxy<>(Boat.Type.class,
            (Supplier<Block>) () -> ModBlocks.WILLOW_PLANKS.get(), "epicworld:willow",
            (Supplier<Item>) () -> ModBlocks.WILLOW_BOAT.get(), (Supplier<Item>) () -> ModBlocks.WILLOW_CHEST_BOAT.get(),
            (Supplier<Item>) () -> Items.STICK, false);
}
