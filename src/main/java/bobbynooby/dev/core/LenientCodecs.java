package bobbynooby.dev.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Codecs that read what they can from a file instead of failing on the first bad part. */
public final class LenientCodecs {
    private LenientCodecs() {}

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

    /**
     * A list that writes each element on its own: one that can't be written is logged ("what" names the kind) and left
     * out, and the rest are saved. Vanilla's own list fails whole on one bad element, and the world's data storage
     * throws on a failed entry, so one stack another mod broke would stop every save of the file it is in and of the
     * files after it. Reading is the plain list codec.
     */
    public static <T> Codec<List<T>> writing(Codec<T> element, String what) {
        Encoder<List<T>> encoder = new Encoder<>() {
            @Override
            public <D> DataResult<D> encode(List<T> values, DynamicOps<D> ops, D prefix) {
                List<D> written = new ArrayList<>();
                for (T value : values) {
                    DataResult<D> result = element.encodeStart(ops, value);
                    result.result().ifPresentOrElse(written::add, () -> Yolt.LOGGER.error(
                            "Leaving out a {} that can't be saved: {}", what, result.error().map(DataResult.Error::message).orElse("?")));
                }
                return ops.mergeToList(prefix, written);
            }
        };
        return Codec.of(encoder, element.listOf());
    }

    /** The same for a map's values: an entry whose value can't be written is logged and left out. */
    public static <K, V> Codec<Map<K, V>> writingMap(Codec<K> keys, Codec<V> values, String what) {
        Codec<Map<K, V>> strict = Codec.unboundedMap(keys, values);
        Encoder<Map<K, V>> encoder = new Encoder<>() {
            @Override
            public <D> DataResult<D> encode(Map<K, V> entries, DynamicOps<D> ops, D prefix) {
                Map<K, V> writable = new LinkedHashMap<>();
                entries.forEach((key, value) -> {
                    DataResult<D> result = values.encodeStart(ops, value);
                    if (result.isSuccess()) {
                        writable.put(key, value);
                    } else {
                        Yolt.LOGGER.error("Leaving out a {} that can't be saved: {}", what,
                                result.error().map(DataResult.Error::message).orElse("?"));
                    }
                });
                return strict.encode(writable, ops, prefix);
            }
        };
        return Codec.of(encoder, strict);
    }

    /** The elements a lenient list read, and whether every one of them was read whole. */
    public record Read<T>(List<T> values, boolean complete) {
    }

    /**
     * A list that decodes each element on its own: a bad element is logged ("what" names the kind) and skipped, so one
     * broken record can't make a whole store start empty. It also tells whether anything was left out, so a store can
     * keep a copy of its file before the next save drops that part for good. An element its codec could read only in
     * part (an error that still carries a value) is kept as read, with a warning that says what was left out.
     */
    public static <T> Codec<Read<T>> read(Codec<T> element, String what) {
        Encoder<Read<T>> encoder = writing(element, what).comap(Read::values);
        Decoder<Read<T>> decoder = Codec.PASSTHROUGH.listOf().map(entries -> {
            List<T> loaded = new ArrayList<>();
            boolean complete = true;
            for (Dynamic<?> entry : entries) {
                DataResult<T> parsed = element.parse(entry);
                complete &= parsed.isSuccess();
                Optional<T> value = parsed.resultOrPartial();
                if (value.isEmpty()) {
                    parsed.error().ifPresent(error -> Yolt.LOGGER.error("Skipping unreadable {} record: {}", what, error.message()));
                } else {
                    parsed.error().ifPresent(error -> Yolt.LOGGER.warn("Reading only part of a {} record: {}", what, error.message()));
                }
                value.ifPresent(loaded::add);
            }
            return new Read<>(loaded, complete);
        });
        return Codec.of(encoder, decoder);
    }
}
