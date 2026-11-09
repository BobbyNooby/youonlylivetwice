package bobbynooby.dev.core;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod id, the shared logger and the id helper every package uses. */
public final class Yolt {
    public static final String MOD_ID = "you-only-live-twice";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Yolt() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
