package me.nyxia.epicworld.client;

import me.nyxia.epicworld.pregen.Pregenerator;
import me.nyxia.epicworld.voxy.LodGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * F3 lines about Epic World's LOD generation and pregeneration. They read the integrated server directly, so they
 * show in single player and when hosting over LAN.
 */
public final class DebugOverlay {
    private DebugOverlay() {}

    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        var left = event.getLeft();
        boolean header = false;
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            LodGenerator lods = LodGenerator.active();
            if (lods != null) {
                var s = lods.stats();
                Component line = switch (s.state()) {
                    case "running" -> Component.translatable("epicworld.debug.lods", s.sent(), String.format(java.util.Locale.ROOT, "%.0f", s.perSecond()),
                            s.viewers(), s.inFlight(), s.cached(), s.radius(),
                            Component.translatable(s.circle() ? "epicworld.shape.circle" : "epicworld.shape.square"), s.threads());
                    case "done" -> Component.translatable("epicworld.debug.lods.done", s.sent());
                    case "idle" -> Component.translatable("epicworld.debug.lods.idle");
                    default -> Component.translatable("epicworld.debug.lods.off", Component.translatable(s.state()));
                };
                left.add("");
                header = true;
                left.add(line.getString());
            }
            Pregenerator task = Pregenerator.task();
            if (task != null) {
                left.add(Component.translatable("epicworld.debug.pregen",
                        String.format(java.util.Locale.ROOT, "%.1f", 100.0 * task.done() / Math.max(1, task.total())), task.done(), task.total(),
                        String.format(java.util.Locale.ROOT, "%.0f", task.perSecond()), task.remaining(),
                        task.paused() ? Component.translatable("epicworld.debug.pregen.paused") : Component.empty()).getString());
            }
        }
        if (LodReceiver.received() > 0) {
            if (!header) left.add("");
            left.add(Component.translatable("epicworld.debug.lods.received", LodReceiver.received(),
                    String.format(java.util.Locale.ROOT, "%.0f", LodReceiver.perSecond())).getString());
        }
    }
}
