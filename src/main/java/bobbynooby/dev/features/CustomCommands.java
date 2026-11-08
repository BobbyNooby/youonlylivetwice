package bobbynooby.dev.features;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.Vec2;

import java.util.Optional;

public class CustomCommands {


    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            dispatcher.register(Commands.literal("yolt")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // Require op
                    .then(Commands.literal("border")
                            .then(Commands.argument("diameter", IntegerArgumentType.integer()) // /yolt border <diameter>
                                    .executes(context -> {

                                        // Extract diameter value
                                        int diameter = IntegerArgumentType.getInteger(context, "diameter");

                                        // Change the config and update the world borders
                                        Settings.get().worldBorder.diameter = diameter;
                                        Settings.save();
                                        WorldBorders.apply(context.getSource().getServer());

                                        // Console logging
                                        YouOnlyLiveTwice.LOGGER.info("{} set the world border diameter to {}", context.getSource().getPlayer().getName() != null ? context.getSource().getPlayer().getName().getString() : "Console", diameter);

                                        // Send feedback
                                        context.getSource().sendSuccess(() -> {
                                            return Component.literal("World border diameter set to: " + diameter);
                                        }, false);

                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("center")
                            .then(Commands.argument("pos", Vec2Argument.vec2()) // /yolt center <x> <z>
                                    .executes(context -> {
                                        // Extract the position
                                        Vec2 pos = Vec2Argument.getVec2(context, "pos");
                                        int x = (int) pos.x;
                                        int z = (int) pos.y;

                                        // Set the world border center for the specified dimensions
                                        MinecraftServer server = context.getSource().getServer();
                                        for (ServerLevel level : server.getAllLevels()) {
                                            if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD) || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)) {
                                                level.getWorldBorder().setCenter(x, z); // Set the center for overworld/nether
                                            } else if (level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
                                                level.getWorldBorder().setCenter(0, 0); // Keep the center at (0, 0) for the end
                                            }
                                        }

                                        // Console logging
                                        YouOnlyLiveTwice.LOGGER.info("{} set the world border center to {}, {}", context.getSource().getPlayer().getName() != null ? context.getSource().getPlayer().getName().getString() : "Console", x, z);

                                        // Send feedback
                                        context.getSource().sendSuccess(() -> {
                                            return Component.literal("World border center set to: " + x + ", " + z);
                                        }, false);

                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("endscaling")
                            .then(Commands.argument("scaling", DoubleArgumentType.doubleArg()) // /yolt endscaling <scaling>
                                    .executes(context -> {
                                        double scaling = DoubleArgumentType.getDouble(context, "scaling");

                                        // Update the scaling value in the config
                                        Settings.get().worldBorder.endScale = scaling;
                                        Settings.save();

                                        // Update the world border sizes
                                        WorldBorders.apply(context.getSource().getServer());

                                        // Console logging
                                        YouOnlyLiveTwice.LOGGER.info("{} set the end world border scaling factor to {}", context.getSource().getPlayer().getName() != null ? context.getSource().getPlayer().getName().getString() : "Console", scaling);

                                        // Send feedback
                                        context.getSource().sendSuccess(() -> {
                                            return Component.literal("End world border scaling factor set to: " + scaling);
                                        }, false);

                                        return 1;
                                    })
                            )
                    ).then(Commands.literal("respawncooldown")
                            .then(Commands.argument("cooldown", IntegerArgumentType.integer()) // /yolt respawncooldown <cooldown>))
                                    .executes(context -> {

                                        // Set the respawn cooldown
                                        int cooldown = IntegerArgumentType.getInteger(context, "cooldown");
                                        Settings.get().deathLockout.cooldownDays = cooldown;
                                        Settings.save();

                                        // Send feedback
                                        context.getSource().sendSuccess(() -> {
                                            return Component.literal("Respawn cooldown set to: " + cooldown);
                                        }, false);
                                        return 1;
                                    })
                            )
                    ).then(Commands.literal("kill")
                            .then(Commands.argument("player", StringArgumentType.string()) // /yolt kill <player>
                                    .executes(context -> {

                                                MinecraftServer server = context.getSource().getServer();

                                                // Get the target player from cache
                                                String playerName = StringArgumentType.getString(context, "player");
                                                Optional<NameAndId> playerProfile = server.services().nameToIdCache().get(playerName);
                                                try {
                                                    ServerPlayer player = server.getPlayerList().getPlayerByName(playerName); // Get the target player from the ServerPlayer

                                                    if (player != null) {


                                                        // Kill the player
                                                        DeathLockout.recordDeath(player);

                                                        // Send feedback
                                                        context.getSource().sendSuccess(() -> {
                                                            return Component.literal("Killed " + player.getGameProfile().name());
                                                        }, false);
                                                    } else if (playerProfile.isPresent()) {

                                                        // Kill the player offline
                                                        DeathLockout.recordDeath(playerProfile.get().id(), playerProfile.get().name());

                                                        // Send feedback
                                                        context.getSource().sendSuccess(() -> {
                                                            return Component.literal("Killed " + playerProfile.get().name());
                                                        }, false);
                                                    } else {
                                                        // No player found
                                                        context.getSource().sendSuccess(() -> {
                                                            return Component.literal("Player" + playerName + " not found");
                                                        }, false);
                                                    }
                                                } catch (Exception e) {
                                                    YouOnlyLiveTwice.LOGGER.error("Failed to kill " + playerName, e);
                                                }

                                                return 1;
                                            }
                                    )
                            )
                    ).then(Commands.literal("unkill")
                            .then(Commands.argument("player", StringArgumentType.string()) // /yolt unkill <player>
                                    .executes(context -> {


                                        MinecraftServer server = context.getSource().getServer();


                                        // Get the target player from cache
                                        String playerName = StringArgumentType.getString(context, "player");
                                        try {
                                            Optional<NameAndId> playerProfile = server.services().nameToIdCache().get(playerName);

                                            if (playerProfile.isPresent()) {

                                                // Revive the player
                                                DeathLockout.revive(playerProfile.get().id());

                                                // Send feedback
                                                context.getSource().sendSuccess(() -> {
                                                    return Component.literal("Revived " + playerProfile.get().name());
                                                }, false);
                                            } else {

                                                // No player found
                                                context.getSource().sendSuccess(() -> {
                                                    return Component.literal("Player" + playerName + " not found");
                                                }, false);

                                            }
                                        } catch (Exception e) {
                                            YouOnlyLiveTwice.LOGGER.error("Failed to unkill " + playerName, e);
                                        }

                                        return 1;
                                    }))
                    )
            );
        });
    }
}
