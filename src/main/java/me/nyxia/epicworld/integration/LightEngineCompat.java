package me.nyxia.epicworld.integration;

import java.util.Map;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;

/**
 * Light Engine (an optional mod, no compile dependency): tells it the radius and colour of Epic World's light sources
 * through its IMC message {@code register_light}. Light Engine reads it on both sides, so client and server agree; the
 * player's own Light Engine config still wins. Radii follow Light Engine's preset (about 1.5 times the vanilla level).
 */
public final class LightEngineCompat {
    private LightEngineCompat() {}

    private static final String LIGHT_ENGINE = "lightengine";

    /** Block, radius (0-255), colour. */
    private static final Object[][] LIGHTS = {
            // Burning cattail fluff: a torch flame, a little warmer.
            {"epicworld:cattail_torch", 22, "#FF8A30"},
            // Glowworms of the glowing caves (and in a jar): the blue-green of New Zealand's glowworm caves.
            {"epicworld:glowworm_jar", 18, "#78E6FF"},
            {"epicworld:glowworms", 8, "#5AD2FF"},
            {"epicworld:glowing_threads", 5, "#8CF0FF"},
    };

    public static void enqueue(InterModEnqueueEvent event) {
        if (!ModList.get().isLoaded(LIGHT_ENGINE)) return;
        for (Object[] light : LIGHTS) {
            InterModComms.sendTo(LIGHT_ENGINE, "register_light", () -> Map.of(
                    "block", light[0], "radius", light[1], "color", light[2]));
        }
    }
}
