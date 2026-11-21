package bobbynooby.dev.gametest;

import bobbynooby.dev.anvils.YoltAnvils;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.elytra.YoltElytra;
import bobbynooby.dev.mending.YoltMending;
import net.minecraft.resources.Identifier;

import java.util.List;

/** What counts as YOLT's own on a player: its id and the ids of the mods its jar holds. */
final class Ours {
    // The tiny mods put their modifiers in their own namespaces, so a check for YOLT's alone would miss them
    private static final List<String> IDS =
            List.of(Yolt.MOD_ID, YoltAnvils.MOD_ID, YoltMending.MOD_ID, YoltElytra.MOD_ID);

    private Ours() {}

    /** True when the id's namespace is one of ours. */
    static boolean is(Identifier id) {
        return IDS.contains(id.getNamespace());
    }

    /** True when the text holds one of our ids. */
    static boolean named(String text) {
        return IDS.stream().anyMatch(text::contains);
    }
}
