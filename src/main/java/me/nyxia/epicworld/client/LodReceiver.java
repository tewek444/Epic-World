package me.nyxia.epicworld.client;

import java.util.concurrent.atomic.AtomicLong;
import me.nyxia.epicworld.config.EpicPerformance;
import me.nyxia.epicworld.voxy.LodPayloads;
import me.nyxia.epicworld.voxy.VoxyBridge;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of the LOD service: says hello to an Epic World server when Voxy is installed, and hands the sections it
 * sends to Voxy for the current dimension.
 */
public final class LodReceiver {
    private LodReceiver() {}

    private static final AtomicLong RECEIVED = new AtomicLong();
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Epic World LOD light");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final long[] HISTORY = new long[6];
    private static long lastSample;

    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        RECEIVED.set(0);
        java.util.Arrays.fill(HISTORY, 0);
        if (!EpicPerformance.get(EpicPerformance.VOXY_RECEIVE) || !VoxyBridge.available()) return;
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(LodPayloads.Hello.TYPE)) return;
        PacketDistributor.sendToServer(new LodPayloads.Hello(LodPayloads.Hello.PROTOCOL));
    }

    public static void receive(LodPayloads.Chunk payload) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.dimension())) return;
        Object world = VoxyBridge.world(level);
        if (world == null) return;
        // Off the game thread: light per section, then Voxy's own queue takes them.
        WORKER.execute(() -> {
            var light = me.nyxia.epicworld.voxy.SkyLight.compute(payload.sections());
            for (LodPayloads.Section s : payload.sections()) {
                VoxyBridge.ingest(world, s.section(), payload.x(), s.y(), payload.z(), null, light.get(s.y()));
            }
        });
        RECEIVED.incrementAndGet();
    }

    /** Chunks received this session, and per second over the last seconds (for F3). */
    static long received() {
        return RECEIVED.get();
    }

    static double perSecond() {
        long now = System.currentTimeMillis();
        if (now - lastSample >= 1000) {
            lastSample = now;
            System.arraycopy(HISTORY, 0, HISTORY, 1, HISTORY.length - 1);
            HISTORY[0] = RECEIVED.get();
        }
        return (HISTORY[0] - HISTORY[HISTORY.length - 1]) / (double) (HISTORY.length - 1);
    }
}
