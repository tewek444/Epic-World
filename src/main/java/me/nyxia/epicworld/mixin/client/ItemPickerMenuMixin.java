package me.nyxia.epicworld.mixin.client;

import me.nyxia.epicworld.block.CreativeSections;
import me.nyxia.epicworld.block.ModBlocks;
import me.nyxia.epicworld.client.CreativeTabSections;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spaces the Epic World tab into sections; remembers the visible row for the header renderer. */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
public abstract class ItemPickerMenuMixin {
    @Shadow
    @Final
    public NonNullList<ItemStack> items;

    @Shadow
    protected abstract int getRowIndexForScroll(float scroll);

    @Inject(method = "scrollTo", at = @At("HEAD"))
    private void epicworld$sections(float scroll, CallbackInfo ci) {
        if (CreativeScreenAccessor.epicworld$selectedTab() == ModBlocks.TAB.get()
                && items.size() == CreativeSections.itemCount()) {
            CreativeTabSections.headerRows = CreativeSections.pad(items);
        }
        CreativeTabSections.currentRow = getRowIndexForScroll(scroll);
    }
}
