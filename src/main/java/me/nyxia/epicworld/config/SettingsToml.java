package me.nyxia.epicworld.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Reads and writes the human-editable TOML form of {@link EpicSettings}. Missing keys keep their defaults. */
public final class SettingsToml {
    private SettingsToml() {}

    private static final Map<String, Set<String>> KNOWN = Map.of(
            "world", Set.of("min_y", "height", "height_limit", "sea_level"),
            "biomes", Set.of("global_size_multiplier", "overrides"),
            "rivers", Set.of("enabled", "min_width", "max_width", "width_variation", "depth_scales_with_width",
                    "connected_river_networks", "allow_mountain_gorges"),
            "lakes", Set.of("enabled", "min_radius", "max_radius", "max_depth", "depth_scales_with_size",
                    "generate_islands", "connect_to_rivers"),
            "oceans", Set.of("enabled", "coverage", "underwater_mountains", "ocean_trenches", "max_depth", "generate_islands"),
            "archipelagos", Set.of("enabled", "min_island_radius", "max_island_radius"),
            "atolls", Set.of("enabled", "min_radius", "max_radius", "generate_lagoons", "generate_coral_reefs"),
            "compatibility", Set.of("terrain_blending", "blending_distance"),
            "meta", Set.of("generator_version", "note"));

    /** Parses TOML. Unknown keys are reported through {@code warnings}; type errors throw. */
    public static EpicSettings parse(String text, Consumer<String> warnings) {
        UnmodifiableConfig c = new TomlParser().parse(new StringReader(text));
        for (var entry : c.valueMap().entrySet()) {
            var known = KNOWN.get(entry.getKey());
            if (known == null) {
                warnings.accept("unknown section [" + entry.getKey() + "]");
            } else if (entry.getValue() instanceof UnmodifiableConfig section) {
                for (String key : section.valueMap().keySet()) {
                    if (!known.contains(key)) warnings.accept("unknown key " + entry.getKey() + "." + key);
                }
            }
        }
        var d = EpicSettings.DEFAULT;
        var world = new EpicSettings.World(
                integer(c, "world.min_y", d.world().minY()),
                integer(c, "world.height", d.world().height()),
                integer(c, "world.height_limit", d.world().heightLimit()),
                integer(c, "world.sea_level", d.world().seaLevel()));
        Map<String, EpicSettings.BiomeOverride> overrides = new LinkedHashMap<>();
        Object raw = c.get(List.of("biomes", "overrides"));
        if (raw instanceof UnmodifiableConfig table) {
            for (var entry : table.valueMap().entrySet()) {
                if (!(entry.getValue() instanceof UnmodifiableConfig o)) {
                    throw new IllegalArgumentException("biomes.overrides." + entry.getKey() + " must be a table");
                }
                for (String key : o.valueMap().keySet()) {
                    if (!Set.of("enabled", "size", "weight").contains(key)) {
                        warnings.accept("unknown key biomes.overrides." + entry.getKey() + "." + key);
                    }
                }
                String id = entry.getKey().contains(":") ? entry.getKey() : "epicworld:" + entry.getKey();
                overrides.put(id.toLowerCase(Locale.ROOT), new EpicSettings.BiomeOverride(
                        Optional.ofNullable(o.<Object>get("enabled")).map(v -> bool(v, id + ".enabled")),
                        Optional.ofNullable(o.<Object>get("size")).map(v -> number(v, id + ".size").doubleValue()),
                        Optional.ofNullable(o.<Object>get("weight")).map(v -> number(v, id + ".weight").intValue())));
            }
        }
        var biomes = new EpicSettings.Biomes(decimal(c, "biomes.global_size_multiplier", d.biomes().globalSizeMultiplier()), overrides);
        var r = d.rivers();
        var rivers = new EpicSettings.Rivers(flag(c, "rivers.enabled", r.enabled()),
                integer(c, "rivers.min_width", r.minWidth()), integer(c, "rivers.max_width", r.maxWidth()),
                decimal(c, "rivers.width_variation", r.widthVariation()),
                flag(c, "rivers.depth_scales_with_width", r.depthScalesWithWidth()),
                flag(c, "rivers.connected_river_networks", r.connectedNetworks()),
                flag(c, "rivers.allow_mountain_gorges", r.allowMountainGorges()));
        var l = d.lakes();
        var lakes = new EpicSettings.Lakes(flag(c, "lakes.enabled", l.enabled()),
                integer(c, "lakes.min_radius", l.minRadius()), integer(c, "lakes.max_radius", l.maxRadius()),
                integer(c, "lakes.max_depth", l.maxDepth()), flag(c, "lakes.depth_scales_with_size", l.depthScalesWithSize()),
                flag(c, "lakes.generate_islands", l.generateIslands()), flag(c, "lakes.connect_to_rivers", l.connectToRivers()));
        var o = d.oceans();
        var oceans = new EpicSettings.Oceans(flag(c, "oceans.enabled", o.enabled()),
                decimal(c, "oceans.coverage", o.coverage()), flag(c, "oceans.underwater_mountains", o.underwaterMountains()),
                flag(c, "oceans.ocean_trenches", o.trenches()), integer(c, "oceans.max_depth", o.maxDepth()),
                flag(c, "oceans.generate_islands", o.generateIslands()));
        var a = d.archipelagos();
        var archipelagos = new EpicSettings.Archipelagos(flag(c, "archipelagos.enabled", a.enabled()),
                integer(c, "archipelagos.min_island_radius", a.minIslandRadius()),
                integer(c, "archipelagos.max_island_radius", a.maxIslandRadius()));
        var t = d.atolls();
        var atolls = new EpicSettings.Atolls(flag(c, "atolls.enabled", t.enabled()),
                integer(c, "atolls.min_radius", t.minRadius()), integer(c, "atolls.max_radius", t.maxRadius()),
                flag(c, "atolls.generate_lagoons", t.generateLagoons()), flag(c, "atolls.generate_coral_reefs", t.generateCoralReefs()));
        var cp = d.compatibility();
        var compatibility = new EpicSettings.Compatibility(flag(c, "compatibility.terrain_blending", cp.terrainBlending()),
                integer(c, "compatibility.blending_distance", cp.blendingDistance()));
        return new EpicSettings(world, biomes, rivers, lakes, oceans, archipelagos, atolls, compatibility).validate();
    }

    /** Generator version recorded in a snapshot, or -1 when absent. */
    public static int generatorVersion(String text) {
        Object value = new TomlParser().parse(new StringReader(text)).get("meta.generator_version");
        return value instanceof Number n ? n.intValue() : -1;
    }

    private static Number number(Object value, String path) {
        if (value instanceof Number n) return n;
        throw new IllegalArgumentException(path + " must be a number (got " + value + ")");
    }

    private static boolean bool(Object value, String path) {
        if (value instanceof Boolean b) return b;
        throw new IllegalArgumentException(path + " must be true or false (got " + value + ")");
    }

    private static int integer(UnmodifiableConfig c, String path, int fallback) {
        Object value = c.get(path);
        if (value == null) return fallback;
        Number n = number(value, path);
        if (n.doubleValue() != Math.rint(n.doubleValue())) throw new IllegalArgumentException(path + " must be a whole number");
        return n.intValue();
    }

    private static double decimal(UnmodifiableConfig c, String path, double fallback) {
        Object value = c.get(path);
        return value == null ? fallback : number(value, path).doubleValue();
    }

    private static boolean flag(UnmodifiableConfig c, String path, boolean fallback) {
        Object value = c.get(path);
        return value == null ? fallback : bool(value, path);
    }

    /** Full commented file. {@code snapshotNote} adds the snapshot header used inside world saves. */
    public static String write(EpicSettings s, boolean snapshot) {
        var b = new StringBuilder();
        b.append("# ==========================================\n# EPIC WORLD\n# World Generation Configuration\n# ==========================================\n");
        if (snapshot) {
            b.append("#\n# СНИМОК НАСТРОЕК ЭТОГО МИРА.\n");
            b.append("# Мир использует только этот файл; глобальный config/epicworld.toml на него не влияет.\n");
            b.append("# Изменение значений здесь — явное обновление генерации: уже созданные чанки не меняются,\n");
            b.append("# новые чанки сглаживаются с ними (раздел [compatibility]).\n");
            b.append("# min_y менять нельзя, height_limit можно только увеличивать: иначе мир не загрузится (защита построек).\n");
            b.append("\n[meta]\ngenerator_version = ").append(EpicSettings.GENERATOR_VERSION).append('\n');
        }
        var w = s.world();
        b.append("""


                # ==========================================
                # 1. WORLD SETTINGS
                # ==========================================

                [world]

                # Минимальная высота мира
                # Должна быть кратна 16
                min_y = %d

                # Верхняя Y-граница, до которой генератор может создавать местность
                # Это координата, а не число блоков: при min_y = -64 и height = 448
                # вертикальный диапазон генерации составляет 512 блоков.
                # Должна быть кратна 16
                height = %d

                # Верхняя граница, до которой игрок может строить (исключительная; последний блок — height_limit - 1).
                # Должна быть кратна 16, не меньше height и не больше 2032 (предел Minecraft).
                height_limit = %d

                # Уровень моря
                sea_level = %d


                # ==========================================
                # 2. BIOME SETTINGS
                # ==========================================

                [biomes]

                # Глобальный множитель площади биомов
                # Диапазон: 0.1 - 100.0
                global_size_multiplier = %s


                # Индивидуальные настройки биомов
                #
                # Пример:
                #
                # [biomes.overrides."epicworld:plains"]
                # enabled = true
                # size = 1.0
                # weight = 40
                #
                # size   - множитель пространственного масштаба областей биома (0.1 - 10);
                #          линейный размер области растёт пропорционально size
                # weight - относительный вес при выборе среди биомов, подходящих по климату и рельефу
                # weight не задаёт минимальное расстояние между областями биома
                # Список ID и значения по умолчанию: README.md мода.
                """.formatted(w.minY(), w.height(), w.heightLimit(), w.seaLevel(), num(s.biomes().globalSizeMultiplier())));
        for (var entry : s.biomes().overrides().entrySet()) {
            b.append("\n[biomes.overrides.\"").append(entry.getKey()).append("\"]\n");
            entry.getValue().enabled().ifPresent(v -> b.append("enabled = ").append(v).append('\n'));
            entry.getValue().size().ifPresent(v -> b.append("size = ").append(num(v)).append('\n'));
            entry.getValue().weight().ifPresent(v -> b.append("weight = ").append(v).append('\n'));
        }
        var r = s.rivers();
        var l = s.lakes();
        var o = s.oceans();
        var a = s.archipelagos();
        var t = s.atolls();
        var c = s.compatibility();
        b.append("""


                # ==========================================
                # 3. RIVER GENERATION
                # ==========================================

                [rivers]

                # Генерация рек
                enabled = %s

                # Минимальная ширина реки (блоки)
                min_width = %d

                # Максимальная ширина реки (блоки)
                max_width = %d

                # Максимальное относительное колебание
                # ширины одной реки (0.0 - 1.0)
                width_variation = %s

                # Глубина зависит от ширины реки
                depth_scales_with_width = %s

                # Формировать связанные речные системы (притоки впадают в более крупные реки)
                connected_river_networks = %s

                # Возможность прорезать горные массивы (ущелья); false — широкие долины в обход вершин
                allow_mountain_gorges = %s


                # ==========================================
                # 4. LAKE GENERATION
                # ==========================================

                [lakes]

                # Генерация озёр
                enabled = %s

                # Минимальный радиус озера
                min_radius = %d

                # Максимальный радиус озера
                max_radius = %d

                # Максимальная глубина
                max_depth = %d

                # Глубина зависит от размера озера
                depth_scales_with_size = %s

                # Генерация островов внутри озёр
                generate_islands = %s

                # Связь озёр с речными системами
                connect_to_rivers = %s


                # ==========================================
                # 5. OCEAN GENERATION
                # ==========================================

                [oceans]

                # Генерация океанов
                enabled = %s

                # Целевая доля океанов (0.0 - 0.95): доля площади, занятой океаническими биомами
                coverage = %s

                # Генерация подводных гор и хребтов
                underwater_mountains = %s

                # Генерация глубоководных впадин
                ocean_trenches = %s

                # Максимальная глубина океана
                max_depth = %d

                # Генерация островов
                generate_islands = %s


                # ==========================================
                # 6. ARCHIPELAGOS
                # ==========================================

                [archipelagos]

                # Генерация архипелагов
                enabled = %s

                # Примерный радиус островов
                min_island_radius = %d
                max_island_radius = %d


                # ==========================================
                # 7. ATOLLS
                # ==========================================

                [atolls]

                # Генерация атоллов
                enabled = %s

                # Радиус атоллов
                min_radius = %d
                max_radius = %d

                # Генерация лагун
                generate_lagoons = %s

                # Генерация коралловых рифов
                generate_coral_reefs = %s


                # ==========================================
                # 8. COMPATIBILITY
                # ==========================================

                [compatibility]

                # Плавный переход между старой
                # и новой генерацией
                terrain_blending = %s

                # Дистанция сглаживания (блоки)
                blending_distance = %d
                """.formatted(r.enabled(), r.minWidth(), r.maxWidth(), num(r.widthVariation()), r.depthScalesWithWidth(),
                r.connectedNetworks(), r.allowMountainGorges(),
                l.enabled(), l.minRadius(), l.maxRadius(), l.maxDepth(), l.depthScalesWithSize(), l.generateIslands(), l.connectToRivers(),
                o.enabled(), num(o.coverage()), o.underwaterMountains(), o.trenches(), o.maxDepth(), o.generateIslands(),
                a.enabled(), a.minIslandRadius(), a.maxIslandRadius(),
                t.enabled(), t.minRadius(), t.maxRadius(), t.generateLagoons(), t.generateCoralReefs(),
                c.terrainBlending(), c.blendingDistance()));
        return b.toString();
    }

    private static String num(double value) {
        String text = Double.toString(value);
        return text.endsWith(".0") || text.contains("E") ? String.format(Locale.ROOT, "%.1f", value) : text;
    }

    /** Collects warnings into a list (handy for tests). */
    public static List<String> warningsOf(String text) {
        var warnings = new ArrayList<String>();
        parse(text, warnings::add);
        return warnings;
    }
}
