package bobbynooby.dev.commands;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.skins.CachedSkin;
import bobbynooby.dev.skins.SkinStore;
import bobbynooby.dev.skins.Skins;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

import static bobbynooby.dev.core.Yolt.LOGGER;

/**
 * /yolt skin: a player wears the skin of a real Mojang account from their next join on, over every other skin. A
 * non-op may set and clear only their own, in the full tree and in CommandRestriction's; ops and the console anyone's.
 */
public final class SkinCommands {
    // What a Mojang account name can be made of. Anything else can't be one, so Mojang isn't asked about it.
    private static final Pattern MOJANG_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private SkinCommands() {}

    /** True when non-ops may pick their own skin. */
    static boolean playersMayPick() {
        return Settings.get().skins.allowPlayerChoice;
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("skin")
                .requires(source -> YoltCommands.GAMEMASTER.test(source) || playersMayPick())
                .then(Commands.literal("set")
                        .then(Commands.argument("mojangName", StringArgumentType.word())
                                .executes(c -> setOwn(c.getSource(), StringArgumentType.getString(c, "mojangName"))))
                        // Another player's skin is for ops and the console
                        .then(Commands.argument("player", StringArgumentType.string())
                                .requires(YoltCommands.GAMEMASTER)
                                .then(Commands.argument("mojangName", StringArgumentType.word())
                                        .executes(c -> setOther(c.getSource(), StringArgumentType.getString(c, "player"),
                                                StringArgumentType.getString(c, "mojangName"))))))
                .then(Commands.literal("clear")
                        .executes(c -> clearOwn(c.getSource()))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .requires(YoltCommands.GAMEMASTER)
                                .executes(c -> clearOther(c.getSource(), StringArgumentType.getString(c, "player"))))));
    }

    /** The /yolt a non-op is sent and may run: setting and clearing their own skin, nothing else. */
    public static LiteralArgumentBuilder<CommandSourceStack> ownSkinCommand() {
        return Commands.literal("yolt")
                .then(Commands.literal("skin")
                        .then(Commands.literal("set")
                                .then(Commands.argument("mojangName", StringArgumentType.word())
                                        .executes(c -> setOwn(c.getSource(), StringArgumentType.getString(c, "mojangName")))))
                        .then(Commands.literal("clear")
                                .executes(c -> clearOwn(c.getSource()))));
    }

    // The console gets vanilla's "a player is required" error
    private static int setOwn(CommandSourceStack source, String mojangName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return choose(source, player.nameAndId(), mojangName, true);
    }

    private static int setOther(CommandSourceStack source, String name, String mojangName) {
        // First: finding the player can ask Mojang about a name vanilla hasn't cached, which skins off must never do
        Optional<String> off = Skins.offReason();
        if (off.isPresent()) {
            return YoltCommands.fail(source, off.get());
        }
        Optional<NameAndId> who = YoltCommands.lookup(source.getServer(), name);
        if (who.isEmpty()) {
            return YoltCommands.fail(source, "Player " + name + " not found.");
        }
        return choose(source, who.get(), mojangName, false);
    }

    private static int choose(CommandSourceStack source, NameAndId who, String mojangName, boolean own) {
        Optional<String> off = Skins.offReason();
        if (off.isPresent()) {
            return YoltCommands.fail(source, off.get());
        }
        if (!MOJANG_NAME.matcher(mojangName).matches()) {
            return YoltCommands.fail(source, noAccount(mojangName));
        }
        MinecraftServer server = source.getServer();
        CompletableFuture<CachedSkin> answer = Skins.lookUp(server, mojangName);
        // A saved answer is there at once. Mojang's own comes later, off the server thread, so the reply waits for it.
        if (answer.isDone()) {
            return answered(source, who, mojangName, own, answer);
        }
        answer.whenCompleteAsync((skin, error) -> answered(source, who, mojangName, own, answer), server);
        return 1;
    }

    private static int answered(CommandSourceStack source, NameAndId who, String mojangName, boolean own,
                                CompletableFuture<CachedSkin> answer) {
        SkinStore store = SkinStore.get(source.getServer());
        CachedSkin skin;
        try {
            skin = answer.join();
            store.putSkin(skin);
        } catch (CompletionException e) {
            // Mojang can't be asked, but the skin saved before for that account still works
            skin = store.skin(mojangName);
            if (skin == null || !skin.exists()) {
                LOGGER.warn("{} could not look up the Mojang account {}: {}", source.getTextName(), mojangName,
                        String.valueOf(cause(e)));
                return YoltCommands.fail(source, "Could not reach Mojang to look up " + mojangName
                        + ", so nothing changed. Try again later.");
            }
        }
        if (!skin.exists()) {
            return YoltCommands.fail(source, noAccount(mojangName));
        }
        store.choose(who.id(), skin.name());
        saveNow(source, who);
        LOGGER.info("{} set the skin of {} to the Mojang account {}", source.getTextName(), who.name(), skin.name());
        return YoltCommands.done(source, own
                ? "Your skin is now " + skin.name() + "'s. Rejoin to see it."
                : who.name() + "'s skin is now " + skin.name() + "'s. It shows once they rejoin.");
    }

    private static int clearOwn(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return clear(source, player.nameAndId(), true);
    }

    private static int clearOther(CommandSourceStack source, String name) {
        Optional<NameAndId> who = YoltCommands.lookup(source.getServer(), name);
        if (who.isEmpty()) {
            return YoltCommands.fail(source, "Player " + name + " not found.");
        }
        return clear(source, who.get(), false);
    }

    // Nothing goes to Mojang, so this works with skins off too
    private static int clear(CommandSourceStack source, NameAndId who, boolean own) {
        if (!SkinStore.get(source.getServer()).clearChoice(who.id())) {
            return YoltCommands.fail(source, own ? "You haven't picked a skin." : who.name() + " hasn't picked a skin.");
        }
        saveNow(source, who);
        LOGGER.info("{} cleared the skin {} picked", source.getTextName(), who.name());
        return YoltCommands.done(source, own
                ? "Your skin is back to the default. Rejoin to see it."
                : who.name() + "'s skin is back to the default. It shows once they rejoin.");
    }

    // The pick is made either way; a failed write leaves it for the world's next save
    private static void saveNow(CommandSourceStack source, NameAndId who) {
        try {
            SkinStore.get(source.getServer()).saveNow(source.getServer());
        } catch (RuntimeException e) {
            LOGGER.error("Could not save {}'s skin pick at once, so it is saved with the world", who.name(), e);
        }
    }

    private static String noAccount(String mojangName) {
        return "No Minecraft account is called " + mojangName + ".";
    }

    private static @Nullable Throwable cause(CompletionException e) {
        return e.getCause() != null ? e.getCause() : e;
    }
}
