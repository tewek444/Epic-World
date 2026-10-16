package me.nyxia.epicworld.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.config.SettingsToml;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Decides which settings a world load uses. A save that already has a snapshot always uses it; only new worlds take
 * the global config. The world directory being loaded is reported by a mixin on {@code LevelStorageAccess}.
 */
public final class WorldSettingsStore {
    private WorldSettingsStore() {}

    public static final String SNAPSHOT = "epicworld/settings.toml";
    private static final String APPLIED = "epicworld/applied.json";

    @Nullable
    private static volatile Path worldDirectory;

    public static void openWorld(Path directory) {
        worldDirectory = directory;
        WorldConversion.prepareSettings(directory);
    }

    /** Writes the world's settings snapshot (used when a conversion adapts the heights to the old world). */
    public static void writeSnapshot(Path dir, EpicSettings settings) throws IOException {
        Path snapshot = dir.resolve(SNAPSHOT);
        Files.createDirectories(snapshot.getParent());
        writeAtomically(snapshot, SettingsToml.write(settings, true));
    }

    public static void closeWorld(Path directory) {
        if (directory.equals(worldDirectory)) worldDirectory = null;
    }

    @Nullable
    public static Path worldDirectory() {
        return worldDirectory;
    }

    public static Path globalConfig() {
        return FMLPaths.CONFIGDIR.get().resolve("epicworld.toml");
    }

    /** Creates {@code config/epicworld.toml} from the documented defaults when it is missing. */
    public static void ensureGlobalConfig() {
        Path file = globalConfig();
        if (Files.exists(file)) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, SettingsToml.write(EpicSettings.DEFAULT, false), StandardCharsets.UTF_8);
        } catch (IOException e) {
            EpicWorld.LOGGER.error("Epic World: cannot create {}", file, e);
        }
    }

    /** Global defaults for new worlds. A broken file is reported and replaced by built-in defaults. */
    public static EpicSettings global() {
        ensureGlobalConfig();
        try {
            return SettingsToml.parse(Files.readString(globalConfig(), StandardCharsets.UTF_8),
                    w -> EpicWorld.LOGGER.warn("Epic World config {}: {}", globalConfig(), w));
        } catch (Exception e) {
            EpicWorld.LOGGER.error("Epic World: config/epicworld.toml is invalid, using built-in defaults for new worlds: {}", e.getMessage());
            return EpicSettings.DEFAULT;
        }
    }

    /**
     * Settings for the world currently being loaded. Throws (aborting the load with a readable message) if the
     * snapshot is unreadable or would cut away existing parts of the world.
     */
    public static EpicSettings current() {
        Path dir = worldDirectory;
        if (dir == null) return global();
        Path snapshot = dir.resolve(SNAPSHOT);
        if (!Files.exists(snapshot)) return global();
        EpicSettings settings;
        try {
            settings = SettingsToml.parse(Files.readString(snapshot, StandardCharsets.UTF_8),
                    w -> EpicWorld.LOGGER.warn("Epic World snapshot {}: {}", snapshot, w));
        } catch (Exception e) {
            throw new IllegalStateException("Epic World: cannot read the world's generation settings " + snapshot
                    + ": " + e.getMessage() + ". Fix the file or restore it from a backup.", e);
        }
        checkBounds(dir, settings);
        return settings;
    }

    /** Refuses settings that would remove blocks of an existing save. */
    private static void checkBounds(Path dir, EpicSettings settings) {
        Path applied = dir.resolve(APPLIED);
        if (!Files.exists(applied)) return;
        int minY;
        int heightLimit;
        try (InputStream in = Files.newInputStream(applied)) {
            JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            minY = json.get("min_y").getAsInt();
            heightLimit = json.get("height_limit").getAsInt();
        } catch (IOException | RuntimeException e) {
            // A damaged record (for example zeroed by a power loss) only disables the check; it is rewritten on start.
            EpicWorld.LOGGER.warn("Epic World: cannot read {}, skipping the height check: {}", applied, e.getMessage());
            return;
        }
        var w = settings.world();
        if (w.minY() != minY) {
            throw new IllegalStateException("Epic World: world.min_y of an existing world cannot change (" + minY + " -> "
                    + w.minY() + "). Existing chunks would lose or gain unfilled sections. Restore min_y = " + minY
                    + " in " + dir.resolve(SNAPSHOT));
        }
        if (w.heightLimit() < heightLimit) {
            throw new IllegalStateException("Epic World: world.height_limit cannot be lowered for an existing world ("
                    + heightLimit + " -> " + w.heightLimit() + "): blocks above the new limit would be lost. Restore height_limit >= "
                    + heightLimit + " in " + dir.resolve(SNAPSHOT));
        }
    }

    /** Called once the server has started with Epic World generation: persist the settings in the save. */
    public static void persist(Path dir, EpicSettings settings) {
        try {
            Path snapshot = dir.resolve(SNAPSHOT);
            Files.createDirectories(snapshot.getParent());
            if (!Files.exists(snapshot)) {
                writeAtomically(snapshot, SettingsToml.write(settings, true));
                EpicWorld.LOGGER.info("Epic World: saved this world's generation settings to {}", snapshot);
            }
            var json = new JsonObject();
            json.addProperty("min_y", settings.world().minY());
            json.addProperty("height_limit", settings.world().heightLimit());
            json.addProperty("generator_version", EpicSettings.GENERATOR_VERSION);
            writeAtomically(dir.resolve(APPLIED), json.toString());
        } catch (IOException e) {
            EpicWorld.LOGGER.error("Epic World: cannot save generation settings in {}", dir, e);
        }
    }

    /** Writes through a temporary file, so a crash or power loss leaves either the old or the new content. */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
