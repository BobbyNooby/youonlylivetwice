package bobbynooby.dev.skins;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinQueueTest {
    // A flood of logins under new names must not queue lookups without end
    @Test
    void pastTheLimitANewNameFailsAtOnce() {
        SkinQueue queue = new SkinQueue(3);
        List<CompletableFuture<CachedSkin>> asked = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            asked.add(queue.ask("Name" + i, CompletableFuture::new));
        }
        assertEquals(3, queue.waiting());
        assertFalse(asked.get(2).isDone(), "a lookup within the limit was refused");
        assertTrue(asked.get(3).isCompletedExceptionally(), "a lookup past the limit was queued");
        assertTrue(asked.get(999).isCompletedExceptionally());
    }

    @Test
    void theSameNameSharesOneLookupWhateverItsCase() {
        SkinQueue queue = new SkinQueue(3);
        int[] lookups = new int[1];
        CompletableFuture<CachedSkin> first = queue.ask("Steve", () -> {
            lookups[0]++;
            return new CompletableFuture<>();
        });
        CompletableFuture<CachedSkin> second = queue.ask("steve", () -> {
            lookups[0]++;
            return new CompletableFuture<>();
        });
        assertSame(first, second);
        assertEquals(1, lookups[0]);
        assertEquals(1, queue.waiting());
    }

    @Test
    void anAnswerOrAFailureFreesItsPlace() {
        SkinQueue queue = new SkinQueue(1);
        CompletableFuture<CachedSkin> answered = queue.ask("One", CompletableFuture::new);
        answered.complete(CachedSkin.noAccount("One", 0L));
        assertEquals(0, queue.waiting());
        CompletableFuture<CachedSkin> failing = queue.ask("Two", CompletableFuture::new);
        failing.completeExceptionally(new IllegalStateException("Mojang is down"));
        assertEquals(0, queue.waiting());
        assertFalse(queue.ask("Three", CompletableFuture::new).isDone(), "the place was not freed");
    }
}
