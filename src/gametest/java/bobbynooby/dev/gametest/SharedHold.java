package bobbynooby.dev.gametest;

import java.util.function.Supplier;

// A flake hunt runs copies of one test at once, each starting when its own chunks load. A copy that kept what it found
// and put that back at its end could find another copy's value and leave it behind. So copies that hold the same
// values across ticks share one hold: the first to take it keeps what was there, and the last to let go puts it back.
// The class doesn't check this: callers must let go once, and only if they took the hold.
final class SharedHold {
    private int holders;
    private Runnable putBack = () -> {};

    // keep reads what is there now and returns how to put it back
    void take(Supplier<Runnable> keep) {
        if (holders++ == 0) {
            putBack = keep.get();
        }
    }

    void release() {
        if (--holders == 0) {
            Runnable last = putBack;
            putBack = () -> {};
            last.run();
        }
    }

    boolean held() {
        return holders > 0;
    }
}
