package bobbynooby.dev.commands;

import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.Optional;

import static bobbynooby.dev.core.Yolt.LOGGER;

/** /yolt grave list and remove. */
final class GraveCommands {
    private GraveCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> yolt) {
        yolt.then(Commands.literal("grave")
                .requires(YoltCommands.GAMEMASTER)
                .then(Commands.literal("list")
                        .executes(c -> graveList(c.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(c -> graveRemove(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos"))))));
    }

    private static int graveList(CommandSourceStack source) {
        Collection<Grave> graves = GraveStore.get(source.getServer()).all();
        if (graves.isEmpty()) {
            // An empty list is still an answer, so the command succeeded
            return YoltCommands.done(source, "No graves.");
        }
        for (Grave grave : graves) {
            BlockPos pos = grave.pos();
            YoltCommands.reply(source, grave.ownerName() + " " + grave.dimension().identifier() + " " + pos.getX() + " " + pos.getY()
                    + " " + pos.getZ());
        }
        return graves.size();
    }

    private static int graveRemove(CommandSourceStack source, BlockPos pos) {
        MinecraftServer server = source.getServer();
        Optional<Grave> grave = GraveStore.get(server).at(source.getLevel().dimension(), pos);
        if (grave.isEmpty()) {
            return YoltCommands.fail(source, "No grave at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".");
        }
        String owner = grave.get().ownerName();
        return switch (Graves.removeAndDrop(server, grave.get())) {
            case Graves.Removal.DimensionNotLoaded ignored ->
                    YoltCommands.fail(source, "Could not remove " + owner + "'s grave: its dimension is not loaded.");
            case Graves.Removal.NoSafeSpot ignored -> YoltCommands.fail(source,
                    "Could not remove " + owner + "'s grave: there is nowhere near it to drop its items safely.");
            case Graves.Removal.Dropped dropped -> {
                String stacks = dropped.stacks() == 1 ? "1 stack" : dropped.stacks() + " stacks";
                LOGGER.info("{} removed {}'s grave at {} {} and dropped {}", source.getTextName(), owner,
                        source.getLevel().dimension().identifier(), pos, stacks);
                yield YoltCommands.done(source, "Removed " + owner + "'s grave and dropped " + stacks + ".");
            }
        };
    }
}
