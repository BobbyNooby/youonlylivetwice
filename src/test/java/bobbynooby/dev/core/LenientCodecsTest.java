package bobbynooby.dev.core;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aListThatWasReadLenientlyWritesTheSameWay() {
        JsonElement written = LenientCodecs.read(SMALL, "number").encodeStart(JsonOps.INSTANCE,
                new LenientCodecs.Read<>(List.of(1, 99, 3), true)).getOrThrow();
        assertEquals("[1,3]", written.toString());
    }
}
