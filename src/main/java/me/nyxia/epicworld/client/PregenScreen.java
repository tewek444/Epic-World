package me.nyxia.epicworld.client;

import me.nyxia.epicworld.network.EpicNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /epicworld gen}: two corners (or a radius around the player), the shape, and start / pause / cancel buttons.
 * The server checks the permission again and runs the task (see {@code Pregenerator}).
 */
public final class PregenScreen extends Screen {
    private static final int DEFAULT_RADIUS = 2000;

    private final EpicNetwork.OpenPregen info;
    private EditBox x1, z1, x2, z2, radius;
    private boolean circle;
    private Component estimate = Component.empty();

    private PregenScreen(EpicNetwork.OpenPregen info) {
        super(Component.translatable("epicworld.pregen.title"));
        this.info = info;
    }

    public static void open(EpicNetwork.OpenPregen info) {
        Minecraft.getInstance().setScreen(new PregenScreen(info));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 50;
        x1 = box(cx - 30, y, info.x() - DEFAULT_RADIUS);
        z1 = box(cx + 45, y, info.z() - DEFAULT_RADIUS);
        y += 26;
        x2 = box(cx - 30, y, info.x() + DEFAULT_RADIUS);
        z2 = box(cx + 45, y, info.z() + DEFAULT_RADIUS);
        y += 30;
        radius = box(cx - 30, y, DEFAULT_RADIUS);
        addRenderableWidget(Button.builder(Component.translatable("epicworld.pregen.around_me"), b -> aroundMe())
                .bounds(cx + 45, y, 110, 20).build());
        y += 30;
        addRenderableWidget(Button.builder(shapeText(), b -> {
            circle = !circle;
            b.setMessage(shapeText());
            update();
        }).bounds(cx - 100, y, 200, 20).build());
        y += 34;
        if (!info.running()) {
            addRenderableWidget(Button.builder(Component.translatable("epicworld.pregen.start"), b -> start())
                    .bounds(cx - 100, y, 200, 20).build());
        } else {
            boolean paused = info.paused();
            addRenderableWidget(Button.builder(Component.translatable(paused ? "epicworld.pregen.resume" : "epicworld.pregen.pause"),
                    b -> send(paused ? EpicNetwork.PregenAction.RESUME : EpicNetwork.PregenAction.PAUSE)).bounds(cx - 100, y, 98, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("epicworld.pregen.cancel"),
                    b -> send(EpicNetwork.PregenAction.CANCEL)).bounds(cx + 2, y, 98, 20).build());
        }
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("epicworld.pregen.close"), b -> onClose())
                .bounds(cx - 100, y, 200, 20).build());
        update();
    }

    private EditBox box(int x, int y, int value) {
        var box = new EditBox(font, x, y, 70, 20, Component.empty());
        box.setValue(Integer.toString(value));
        box.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{0,8}"));
        box.setResponder(s -> update());
        addRenderableWidget(box);
        return box;
    }

    private Component shapeText() {
        return Component.translatable("epicworld.pregen.shape", Component.translatable(circle ? "epicworld.shape.circle" : "epicworld.shape.square"));
    }

    private void aroundMe() {
        Integer r = parse(radius);
        if (r == null) return;
        r = Math.abs(r);
        x1.setValue(Integer.toString(info.x() - r));
        z1.setValue(Integer.toString(info.z() - r));
        x2.setValue(Integer.toString(info.x() + r));
        z2.setValue(Integer.toString(info.z() + r));
    }

    private void update() {
        if (x1 == null || z2 == null) return;
        Integer a = parse(x1), b = parse(z1), c = parse(x2), d = parse(z2);
        if (a == null || b == null || c == null || d == null) {
            estimate = Component.translatable("epicworld.pregen.invalid");
            return;
        }
        long w = Math.abs((long) c - a) + 1;
        long h = Math.abs((long) d - b) + 1;
        long chunks = ((Math.abs((long) (c >> 4) - (a >> 4))) + 1) * ((Math.abs((long) (d >> 4) - (b >> 4))) + 1);
        if (circle) chunks = Math.round(chunks * Math.PI / 4);
        // About 150 chunks per second with fast generation on a modern 8-core CPU.
        long seconds = chunks / 150;
        String time = seconds < 3600 ? (seconds / 60) + " min" : (seconds / 3600) + " h " + (seconds % 3600 / 60) + " min";
        estimate = Component.translatable("epicworld.pregen.estimate", chunks, w, h, time);
    }

    private static Integer parse(EditBox box) {
        try {
            return Integer.parseInt(box.getValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void start() {
        Integer a = parse(x1), b = parse(z1), c = parse(x2), d = parse(z2);
        if (a == null || b == null || c == null || d == null) return;
        PacketDistributor.sendToServer(new EpicNetwork.PregenAction(EpicNetwork.PregenAction.START, a, b, c, d, circle));
        onClose();
    }

    private void send(int action) {
        PacketDistributor.sendToServer(new EpicNetwork.PregenAction(action, 0, 0, 0, 0, false));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = width / 2;
        g.drawCenteredString(font, title, cx, 15, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("epicworld.pregen.hint"), cx, 30, 0xA0A0A0);
        g.drawString(font, Component.translatable("epicworld.pregen.from"), cx - 155, 56, 0xFFFFFF);
        g.drawString(font, Component.translatable("epicworld.pregen.to"), cx - 155, 82, 0xFFFFFF);
        g.drawString(font, Component.translatable("epicworld.pregen.radius"), cx - 155, 112, 0xFFFFFF);
        g.drawCenteredString(font, info.running() ? info.status() : estimate, cx, height - 20, 0xE0E0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
