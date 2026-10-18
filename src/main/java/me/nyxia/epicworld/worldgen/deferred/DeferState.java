package me.nyxia.epicworld.worldgen.deferred;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Stored in a chunk whose deep part was deferred: everything below {@code top} is generated in stages.
 * {@link Stage#PLACEHOLDER}: still the placeholder. {@link Stage#NOISE}: terrain, caves, aquifers and carvers are in,
 * features are not (they wait for the neighbours' noise). {@link Stage#DONE}: complete; kept so that features of
 * neighbours still decorating their own depth may write below {@code top} here, and features of other chunks may not.
 */
public record DeferState(int top, Stage stage) {
    public enum Stage { PLACEHOLDER, NOISE, DONE }

    public static final DeferState NONE = new DeferState(Integer.MIN_VALUE, Stage.DONE);

    public static final Codec<DeferState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("top").forGetter(DeferState::top),
            Codec.INT.xmap(n -> Stage.values()[n], Stage::ordinal).fieldOf("stage").forGetter(DeferState::stage)
    ).apply(i, DeferState::new));

    public boolean deferred() {
        return top != Integer.MIN_VALUE;
    }

    public DeferState with(Stage next) {
        return new DeferState(top, next);
    }
}
