package me.nyxia.epicworld.mixin.client;

import me.nyxia.epicworld.block.ModBlocks;
import me.nyxia.epicworld.client.CreativeTabSections;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the section headers of the Epic World tab under tooltips (renderLabels runs before them). */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeTabSectionsMixin {
    @Shadow
    private static CreativeModeTab selectedTab;

    @Inject(method = "renderLabels", at = @At("TAIL"))
    private void epicworld$headers(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (selectedTab == ModBlocks.TAB.get()) CreativeTabSections.render(graphics);
    }
}
