package bobbynooby.dev.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Encoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Codecs that read what they can from a file instead of failing on the first bad part. */
public final class LenientCodecs {
    private LenientCodecs() {
    }

    /**
     * A list inside a record that decodes each element on its own: a bad element is logged ("what" names the kind) and
     * skipped, so it can't take the others with it. A list that left anything out reads as an error that still carries
     * the rest, so the record counts as read in part and its store can keep a copy of the file. Encoding is the plain
     * list codec.
     */
    public static <T> Codec<List<T>> list(Codec<T> element, String what) {
        return read(element, what).<List<T>>flatXmap(
                loaded -> loaded.complete() ? DataResult.success(loaded.values())
                        : DataResult.error(() -> "Left out unreadable " + what + " records", loaded.values()),
                values -> DataResult.success(new Read<T>(values, true)));
    }

    /** The elements a lenient list read, and whether every one of them was read whole. */
    public record Read<T>(List<T> values, boolean complete) {
    }

    /**
     * A list that decodes each element on its own: a bad element is logged ("what" names the kind) and skipped, so one
     * broken record can't make a whole store start empty. It also tells whether anything was left out, so a store can
     * keep a copy of its file before the next save drops that part for good. An element its codec could read only in
     * part (an error that still carries a value) is kept as read, and that codec logs what it left out.
     */
    public static <T> Codec<Read<T>> read(Codec<T> element, String what) {
        Encoder<Read<T>> encoder = element.listOf().comap(Read::values);
        Decoder<Read<T>> decoder = Codec.PASSTHROUGH.listOf().map(entries -> {
            List<T> loaded = new ArrayList<>();
            boolean complete = true;
            for (Dynamic<?> entry : entries) {
                DataResult<T> parsed = element.parse(entry);
                complete &= parsed.isSuccess();
                Optional<T> value = parsed.resultOrPartial();
                if (value.isEmpty()) {
                    parsed.error().ifPresent(error -> Yolt.LOGGER.error("Skipping unreadable {} record: {}", what, error.message()));
                }
                value.ifPresent(loaded::add);
            }
            return new Read<>(loaded, complete);
        });
        return Codec.of(encoder, decoder);
    }
}
