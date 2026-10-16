package me.nyxia.epicworld.datagen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.dev.TerrainMaps;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;

/** Writes overview maps and terrain statistics for three seeds to {@code build/reports/terrain}. */
final class TerrainReport implements DataProvider {
    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path out = Path.of("..", "build", "reports", "terrain");
                Files.createDirectories(out);
                var report = new StringBuilder();
                for (long seed : new long[]{1, 42, 8675309}) report.append(TerrainMaps.render(out, seed, EpicSettings.DEFAULT));
                Files.writeString(out.resolve("maps.txt"), report);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot write terrain report", e);
            }
        });
    }

    @Override
    public String getName() {
        return "Epic World terrain report";
    }
}
