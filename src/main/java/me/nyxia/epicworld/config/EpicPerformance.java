package me.nyxia.epicworld.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/epicworld-performance.toml}: how fast and with which integrations the world is generated. Nothing here
 * changes the terrain itself, so these settings are not part of a world's settings snapshot and can change any time.
 * System properties ({@code -Depicworld.parallelSteps}, {@code -Depicworld.solidTop}, {@code -Depicworld.deferDepth})
 * still override them, for tests and benchmarks.
 */
public final class EpicPerformance {
    private EpicPerformance() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue FAST_GENERATION;
    public static final ModConfigSpec.BooleanValue DEFERRED_CAVES;
    public static final ModConfigSpec.BooleanValue VOXY_ENABLED;
    public static final ModConfigSpec.IntValue VOXY_RADIUS;
    public static final ModConfigSpec.IntValue VOXY_THREADS;
    public static final ModConfigSpec.BooleanValue VOXY_FEATURES;
    public static final ModConfigSpec.EnumValue<Shape> VOXY_SHAPE;
    public static final ModConfigSpec.BooleanValue SODIUM_MENU;

    static {
        var b = new ModConfigSpec.Builder();
        b.comment("Скорость генерации. Ничего здесь не меняет рельеф: настройки можно менять в любой момент.").push("performance");
        FAST_GENERATION = b.comment(
                "Ускоренная генерация: этапы генерации чанков идут параллельно (в ванили поверхность, карверы и фичи",
                "всех чанков стоят в одну очередь), шум считается только до высоты, выше которой может быть лишь воздух.",
                "Мир получается таким же, как без неё (порядок постановки руд и деревьев на стыках чанков может",
                "отличаться, как и между двумя обычными генерациями). Выключайте, только если другой мод генерации",
                "рассчитывает на последовательный порядок этапов.")
                .define("fast_generation", true);
        DEFERRED_CAVES = b.comment(
                "Отложенная генерация глубины: у новых чанков сразу генерируется только верх (поверхность и около 48",
                "блоков под ней), глубина с пещерами достраивается, когда игрок спускается рядом. Ещё примерно в полтора",
                "раза быстрее, но в концах пещер, уходящих вниз, видна заглушка из глубинного сланца, пока игрок не",
                "подойдёт. ВНИМАНИЕ: выключить можно в любой момент (недостроенные чанки всё равно достроятся), но если",
                "удалить мод из мира, где это было включено, недостроенная глубина навсегда останется сланцем.")
                .define("deferred_caves", false);
        b.pop();

        b.comment("Своя генерация лодов для Voxy (вместо Voxy World Gen). Лоды строятся из верхней части чанков,",
                "которые не сохраняются на диск: когда игрок дойдёт до места, настоящий чанк сгенерируется с тем же рельефом.",
                "Работает в одиночной игре и на сервере в локальной сети (на выделенном сервере лоды не передаются).").push("voxy");
        VOXY_ENABLED = b.comment("Включить, если установлен Voxy.").define("enabled", true);
        VOXY_RADIUS = b.comment("Радиус генерации лодов вокруг игрока, в чанках.").defineInRange("radius", 256, 16, 2048);
        VOXY_THREADS = b.comment("Потоков для генерации лодов (0 = половина ядер процессора).").defineInRange("threads", 0, 0, 64);
        VOXY_SHAPE = b.comment("Форма области лодов: CIRCLE (круг, как прорисовка Minecraft) или SQUARE (квадрат).")
                .defineEnum("shape", Shape.CIRCLE);
        VOXY_FEATURES = b.comment("Деревья, цветы и прочие фичи в лодах (без них лоды строятся быстрее, но лес выглядит голым).")
                .define("features", true);
        b.pop();

        b.comment("Встраивание в интерфейс других модов.").push("integration");
        SODIUM_MENU = b.comment(
                "Страница Epic World в настройках видео Sodium. Если после обновления Sodium игра падает или меню",
                "ведёт себя странно, выключите здесь.")
                .define("sodium_menu", true);
        b.pop();
        SPEC = b.build();
    }

    public enum Shape { CIRCLE, SQUARE }

    /** The value of a setting, or its default before the config file is loaded. */
    public static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }

    /** A system property, if set, overrides the setting (tests and benchmarks). */
    public static boolean flag(String property, ModConfigSpec.BooleanValue value) {
        String override = System.getProperty(property);
        return override != null ? Boolean.parseBoolean(override) : get(value);
    }

    public static boolean fastGeneration() {
        return flag("epicworld.fastGeneration", FAST_GENERATION);
    }

    public static boolean deferredCaves() {
        return flag("epicworld.deferDepth", DEFERRED_CAVES);
    }

    /** Saves a changed value (from an in-game menu). */
    public static <T> void set(ModConfigSpec.ConfigValue<T> value, T newValue) {
        value.set(newValue);
        SPEC.save();
    }
}
