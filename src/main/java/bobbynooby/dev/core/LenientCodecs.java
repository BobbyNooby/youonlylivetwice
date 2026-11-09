package bobbynooby.dev.core;

import bobbynooby.dev.YouOnlyLiveTwice;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;

import java.util.ArrayList;
import java.util.List;

/** Codecs that read what they can from a file instead of failing on the first bad part. */
public final class LenientCodecs {
    private LenientCodecs() {
    }

    /**
     * A list that decodes each element on its own: a bad element is logged ("what" names the kind) and skipped,
     * so one broken record can't make a whole store start empty. Encoding is the plain list codec.
     */
    public static <T> Codec<List<T>> list(Codec<T> element, String what) {
        return Codec.of(element.listOf(), Codec.PASSTHROUGH.listOf().map(entries -> {
            List<T> loaded = new ArrayList<>();
            for (Dynamic<?> entry : entries) {
                element.parse(entry)
                        .resultOrPartial(error -> YouOnlyLiveTwice.LOGGER.error("Skipping unreadable {} record: {}", what, error))
                        .ifPresent(loaded::add);
            }
            return loaded;
        }));
    }
}
