package me.nyxia.epicworld.client;

import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.config.EpicPerformance;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;

/**
 * Sodium creates this class (see {@code sodium:config_api_user} in neoforge.mods.toml) when it is installed. The page
 * itself is built by {@link SodiumPage}, only when {@code integration.sodium_menu} is on, and any error from a changed
 * Sodium API is caught so the game keeps running without the page.
 */
public final class SodiumOptions implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!EpicPerformance.get(EpicPerformance.SODIUM_MENU)) return;
        try {
            SodiumPage.register(builder);
        } catch (Throwable e) {
            EpicWorld.LOGGER.warn("Epic World: could not add the page to Sodium's settings (set integration.sodium_menu = false"
                    + " in config/epicworld-performance.toml to stop trying)", e);
        }
    }
}
