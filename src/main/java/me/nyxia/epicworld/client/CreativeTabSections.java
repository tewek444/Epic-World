package me.nyxia.epicworld.client;

import me.nyxia.epicworld.block.CreativeSections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Client state and drawing of the section headers in the Epic World creative tab. */
public final class CreativeTabSections {
    private CreativeTabSections() {}

    public static int[] headerRows = new int[0];
    public static int currentRow;

    /** Draws headers in GUI-relative coordinates (called from renderLabels). */
    public static void render(GuiGraphics graphics) {
        var font = Minecraft.getInstance().font;
        var sections = CreativeSections.values();
        for (int i = 0; i < sections.length && i < headerRows.length; i++) {
            int row = headerRows[i] - currentRow;
            if (row < 0 || row > 4) continue;
            int x = 9;
            int y = 18 + row * 18;
            int color = sections[i].color;
            int light = brighten(color, 1.35f);
            int dark = brighten(color, 0.6f);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.fill(x - 1, y - 1, x + 161, y + 17, dark);
            graphics.fillGradient(x, y, x + 160, y + 16, light, color);
            graphics.fill(x, y + 15, x + 160, y + 16, dark);
            graphics.pose().popPose();
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 210);
            graphics.renderItem(sections[i].icon(), x, y);
            graphics.drawString(font, sections[i].title(), x + 20, y + 4, 0xFFFFFFFF, true);
            graphics.pose().popPose();
        }
    }

    private static int brighten(int argb, float factor) {
        int r = Math.min(255, (int) (((argb >> 16) & 255) * factor));
        int g = Math.min(255, (int) (((argb >> 8) & 255) * factor));
        int b = Math.min(255, (int) ((argb & 255) * factor));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
