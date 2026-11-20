package bobbynooby.dev.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LenientCodecsTest {
    // Stands in for an item stack another mod broke: a number over 10 can't be written
    private static final Codec<Integer> SMALL = Codec.intRange(0, 10);

    // The world's data storage throws on an entry that fails to encode, which would stop every save
    @Test
    void aListStillSavesWhenOneElementCantBeWritten() {
        assertTrue(SMALL.listOf().encodeStart(JsonOps.INSTANCE, List.of(1, 99, 3)).isError(), "the plain list codec wrote it");
        JsonElement written = LenientCodecs.writing(SMALL, "number").encodeStart(JsonOps.INSTANCE, List.of(1, 99, 3)).getOrThrow();
        assertEquals("[1,3]", written.toString());
        assertEquals(List.of(1, 3), LenientCodecs.writing(SMALL, "number").parse(JsonOps.INSTANCE, written).getOrThrow());
    }

    @Test
    void aMapStillSavesWhenOneValueCantBeWritten() {
        Map<String, Integer> entries = new LinkedHashMap<>();
        entries.put("head", 1);
        entries.put("chest", 99);
        entries.put("feet", 3);
        Codec<Map<String, Integer>> codec = LenientCodecs.writingMap(Codec.STRING, SMALL, "number");
        JsonElement written = codec.encodeStart(JsonOps.INSTANCE, entries).getOrThrow();
        assertEquals("{\"head\":1,\"feet\":3}", written.toString());
        assertEquals(Map.of("head", 1, "feet", 3), codec.parse(JsonOps.INSTANCE, written).getOrThrow());
    }

    // Stands in for a record read only in part, such as a grave with one unreadable item: a number over 10 reads as 10,
    // with an error that still carries it
    private static final Codec<Integer> CLAMPED = Codec.INT.flatXmap(
            value -> value > 10 ? DataResult.error(() -> "over 10", 10) : DataResult.success(value),
            DataResult::success);

    // One record of the wrong type and one out of range are left out, and the rest still load. The read says something
    // was left out, so the store keeps a copy of its file; the list form says it as an error that carries the rest.
    @Test
    void aBadRecordIsSkippedAndTheReadSaysSo() {
        JsonElement saved = JsonParser.parseString("[1, \"x\", 99, 3]");
        LenientCodecs.Read<Integer> read = LenientCodecs.read(SMALL, "number").parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(List.of(1, 3), read.values());
        assertFalse(read.complete());

        DataResult<List<Integer>> list = LenientCodecs.list(SMALL, "number").parse(JsonOps.INSTANCE, saved);
        assertTrue(list.isError());
        assertEquals(Optional.of(List.of(1, 3)), list.resultOrPartial());

        // A list with nothing wrong reads whole, as a plain success
        LenientCodecs.Read<Integer> whole = LenientCodecs.read(SMALL, "number")
                .parse(JsonOps.INSTANCE, JsonParser.parseString("[1, 3]")).getOrThrow();
        assertEquals(new LenientCodecs.Read<>(List.of(1, 3), true), whole);
        assertEquals(List.of(1, 3), LenientCodecs.list(SMALL, "number").parse(JsonOps.INSTANCE, JsonParser.parseString("[1, 3]"))
                .getOrThrow());
    }

    // A record read in part is kept as read, but the read still counts as incomplete, since the next save would drop
    // what was left out of it
    @Test
    void aPartlyReadRecordIsKeptButMarksTheReadIncomplete() {
        LenientCodecs.Read<Integer> read = LenientCodecs.read(CLAMPED, "number")
                .parse(JsonOps.INSTANCE, JsonParser.parseString("[1, 99, 3]")).getOrThrow();
        assertEquals(List.of(1, 10, 3), read.values());
        assertFalse(read.complete());
    }

    @Test
    void aListThatWasReadLenientlyWritesTheSameWay() {
        JsonElement written = LenientCodecs.read(SMALL, "number").encodeStart(JsonOps.INSTANCE,
                new LenientCodecs.Read<>(List.of(1, 99, 3), true)).getOrThrow();
        assertEquals("[1,3]", written.toString());
    }
}
