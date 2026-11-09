package bobbynooby.dev.core;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * The two version fields on YOLT's saved-data stores. "format" is the layout of our own fields; "data_version" is
 * the Minecraft data version the stored items were saved at. Loading brings an older store up to date before it is
 * decoded: our own steps first, then vanilla's item fixer on every stored item. Vanilla's fixers only reach items
 * inside vanilla's own data, so without this a changed item format would make the item unreadable, and it would be
 * dropped.
 */
public final class StoreVersions {
    // Minecraft 26.3's data version. Every store saved before these fields existed was saved on 26.3.
    static final int BEFORE_VERSIONING = 5023;
    // Every store saved before the field existed has the first format
    private static final int FIRST_FORMAT = 1;
    private static final String FORMAT = "format";
    private static final String DATA_VERSION = "data_version";

    private static final AtomicLong FIXED_ITEMS = new AtomicLong();

    private StoreVersions() {
    }

    /** A loaded store's records, and whether the file came from a newer YOLT or Minecraft than this one. */
    public record Loaded<R>(R records, boolean fromNewer) {
    }

    /** Where a store keeps its items: runs the fix on each stored item and returns the updated data. */
    @FunctionalInterface
    public interface Items {
        Dynamic<?> fixEach(Dynamic<?> data, UnaryOperator<Dynamic<?>> fix);
    }

    public static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** How many stored items the item fixer ran on without failing since the server started; tests read it. */
    public static long itemsFixed() {
        return FIXED_ITEMS.get();
    }

    /**
     * Wraps a store's records with the version fields. Data from a newer version gets no steps and no fixes, since
     * nothing can be moved back, and decodes as far as it can.
     */
    public static <R> Codec<Loaded<R>> codec(String what, int format, List<Migrations.Step<Dynamic<?>>> steps,
                                              Items items, MapCodec<R> records) {
        Codec<R> recordsCodec = records.codec();
        Encoder<Loaded<R>> encoder = new Encoder<>() {
            @Override
            public <T> DataResult<T> encode(Loaded<R> input, DynamicOps<T> ops, T prefix) {
                return recordsCodec.encode(input.records(), ops, prefix)
                        .flatMap(map -> ops.mergeToMap(map, ops.createString(FORMAT), ops.createInt(format)))
                        .flatMap(map -> ops.mergeToMap(map, ops.createString(DATA_VERSION), ops.createInt(currentDataVersion())));
            }
        };
        Decoder<Loaded<R>> decoder = new Decoder<>() {
            @Override
            public <T> DataResult<Pair<Loaded<R>, T>> decode(DynamicOps<T> ops, T input) {
                Dynamic<T> data = new Dynamic<>(ops, input);
                int savedFormat = data.get(FORMAT).asInt(FIRST_FORMAT);
                int savedDataVersion = data.get(DATA_VERSION).asInt(BEFORE_VERSIONING);
                int current = currentDataVersion();
                Dynamic<?> upgraded = data;
                if (savedFormat <= format) {
                    upgraded = Migrations.run(what + " file", savedFormat, format, steps, upgraded);
                }
                if (savedDataVersion < current) {
                    Yolt.LOGGER.info("Running the {} file's items through Minecraft's data fixer, from data version {} to {}",
                            what, savedDataVersion, current);
                    upgraded = items.fixEach(upgraded, stack -> fixItem(stack, savedDataVersion, current));
                }
                boolean fromNewer = savedFormat > format || savedDataVersion > current;
                return recordsCodec.parse(upgraded.convert(ops))
                        .map(loaded -> Pair.of(new Loaded<R>(loaded, fromNewer), input));
            }
        };
        return Codec.of(encoder, decoder);
    }

    /** Runs the fix on each element of the list under the key. Anything that isn't a list stays as it is. */
    public static Dynamic<?> eachInList(Dynamic<?> data, String key, UnaryOperator<Dynamic<?>> fix) {
        return data.update(key, list -> fixList(list, fix));
    }

    /** Runs the fix on each value of the map under the key. Anything that isn't a map stays as it is. */
    public static Dynamic<?> eachInMap(Dynamic<?> data, String key, UnaryOperator<Dynamic<?>> fix) {
        return data.update(key, map -> map.updateMapValues(entry -> entry.mapSecond(fix)));
    }

    private static <T> Dynamic<T> fixList(Dynamic<T> list, UnaryOperator<Dynamic<?>> fix) {
        return list.asStreamOpt().result()
                .map(elements -> list.createList(elements.map(fix)))
                .orElse(list);
    }

    // The same fixer the server was started with, run on plain NBT as vanilla runs it on saved files. A stack the
    // fixer can't handle is read as it was saved.
    private static <T> Dynamic<T> fixItem(Dynamic<T> stack, int from, int to) {
        try {
            Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.ITEM_STACK, stack.convert(NbtOps.INSTANCE), from, to);
            FIXED_ITEMS.incrementAndGet();
            return fixed.convert(stack.getOps());
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not run a stored item through the data fixer, reading it as it was saved", e);
            return stack;
        }
    }
}
