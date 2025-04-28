package bobbynooby.dev.features;

import bobbynooby.dev.YouOnlyLiveTwice;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.command.argument.Vec2ArgumentType;
import net.minecraft.util.math.Vec2f;
import net.minecraft.world.dimension.DimensionTypes;

public class CustomCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("yolt")
                    .requires(source -> source.hasPermissionLevel(2)) // Require op
                    .then(CommandManager.literal("border")
                            .then(CommandManager.argument("diameter", IntegerArgumentType.integer()) // /yolt border <diameter>
                                    .executes(context -> {

                                        // Extract diameter value
                                        int diameter = IntegerArgumentType.getInteger(context, "diameter");

                                        // Change the config and update the world borders
                                        Config.setWorldDiameter(diameter);
                                        WorldBorders.setupWorldBorder(context.getSource().getServer());

                                        // Console logging
                                        YouOnlyLiveTwice.LOGGER.info("{} set the world border diameter to {}", context.getSource().getPlayer().getName() != null ? context.getSource().getPlayer().getName().getString() : "Console", diameter);

                                        // Send feedback
                                        context.getSource().sendFeedback(() -> {
                                            return Text.of("World border diameter set to: " + diameter);
                                        }, false);

                                        return 1;
                                    })
                            )
                    )
                    .then(CommandManager.literal("center")
                            .then(CommandManager.argument("pos", Vec2ArgumentType.vec2()) // /yolt center <x> <z>
                                    .executes(context -> {
                                        // Extract the position
                                        Vec2f pos = Vec2ArgumentType.getVec2(context, "pos");
                                        int x = (int) pos.x;
                                        int z = (int) pos.y;

                                        // Set the world border center for the specified dimensions
                                        MinecraftServer server = context.getSource().getServer();
                                        for (ServerWorld world : server.getWorlds()) {
                                            if (world.getDimensionEntry().matchesKey(DimensionTypes.OVERWORLD) || world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) {
                                                world.getWorldBorder().setCenter(x, z); // Set the center for overworld/nether
                                            } else if (world.getDimensionEntry().matchesKey(DimensionTypes.THE_END)) {
                                                world.getWorldBorder().setCenter(0, 0); // Keep the center at (0, 0) for the end
                                            }
                                        }

                                        // Console logging
                                        YouOnlyLiveTwice.LOGGER.info("{} set the world border center to {}, {}", context.getSource().getPlayer().getName() != null ? context.getSource().getPlayer().getName().getString() : "Console", x, z);

                                        // Send feedback
                                        context.getSource().sendFeedback(() -> {
                                            return Text.of("World border center set to: " + x + ", " + z);
                                        }, false);

                                        return 1;
                                    })
                            )
                    )
                    .then(CommandManager.literal("endscaling")
                            .then(CommandManager.argument("scaling", DoubleArgumentType.doubleArg()) // /yolt endscaling <scaling>
                                    .executes(context -> {
                                        double scaling = DoubleArgumentType.getDouble(context, "scaling");

                                        // Update the scaling value in the config
                                        Config.setEndBorderScale(scaling);

                                        // Update the world border sizes
                                        WorldBorders.setupWorldBorder(context.getSource().getServer());

                                        // Console logging
                                        YouOnlyLiveTwice.LOGGER.info("{} set the end world border scaling factor to {}", context.getSource().getPlayer().getName() != null ? context.getSource().getPlayer().getName().getString() : "Console", scaling);

                                        // Send feedback
                                        context.getSource().sendFeedback(() -> {
                                            return Text.of("End world border scaling factor set to: " + scaling);
                                        }, false);

                                        return 1;
                                    })
                            )
                    )
            );
        });
    }
}
