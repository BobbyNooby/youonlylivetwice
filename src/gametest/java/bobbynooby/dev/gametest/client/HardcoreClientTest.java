package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.hardcore.BloodMoon;
import bobbynooby.dev.hardcore.ColdMaths;
import bobbynooby.dev.hardcore.Curse;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Slowdowns;
import bobbynooby.dev.revive.ReviveLook;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

// What a vanilla client shows of the hardcore layer, one screenshot each. Every feature is on only for its own shot, and
// the state is set straight on the server rather than played out, so the shots come out the same every run.
public class HardcoreClientTest implements FabricClientGameTest {
    // Fixed views, so the two field of view shots differ only by the slowdown
    private static final float SOUTH = 0.0F;
    private static final float NORTH = 180.0F;
    private static final float LEVEL = 0.0F;
    private static final double CLOSE = 0.01;
    // Long enough for fades and lerps to finish: the boss bar's darkening, the field of view, the frost overlay
    private static final int SETTLE_TICKS = 40;
    // The blood moon's darkening takes about 80 ticks to fade once its bar is gone
    private static final int UNDARKEN_TICKS = 100;
    // The pose and the camera apply at once, while vanilla's sleep fade darkens the screen more each tick in bed
    private static final int ASLEEP_TICKS = 5;
    private static final int LONG_TIMEOUT = 200;
    private static final int RELOAD_TIMEOUT = 1200;
    private static final int HALF_FROZEN_TICKS = ColdMaths.frozenTicks(0.5);
    private static final int TOLERANCE_TICKS = 4;
    private static final int LOST_HEARTS = 1;
    private static final float CURSED_MAX_HEALTH = 18.0F;
    private static final float FULL_HEALTH = 20.0F;
    private static final double SLOWED = 0.5;
    // Vanilla would price a rename of an item with this much prior work at 11; the flat price is the rename cost alone
    private static final int PRIOR_WORK = 10;
    // Enough to pay the flat price, so the price line is green
    private static final int XP_LEVELS = 5;
    private static final String NEW_NAME = "Frostbite";
    private static final int USE_DISTANCE = 2;
    private static final String PACK_FOLDER = "yolt-revive-paper";
    private static final Path PACK_SOURCE = Path.of("pack", "revive-paper");

    private record Stage(UUID self, Vec3 home, BlockPos homeBlock, GameType modeWas, int levelsWas) {}

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientTests.withServer(ctx, (server, connection) -> {
            HardcoreConfig.HardcoreSection was = server.computeOnServer(s -> copy(Settings.get().hardcore));
            boolean textureWas = server.computeOnServer(s -> Settings.get().resourcePack.reviveTexture);
            CameraType cameraWas = ctx.computeOnClient(mc -> mc.options.getCameraType());
            Stage stage = server.computeOnServer(s -> {
                ServerPlayer player = connection.getServerPlayer();
                Stage made = new Stage(player.getUUID(), player.position(), player.blockPosition(), player.gameMode(),
                        player.experienceLevel);
                // Hearts only show in survival, and every hardcore rule leaves other modes alone
                player.setGameMode(GameType.SURVIVAL);
                // The bed needs daylight to show a nap; the world is new for each test, so the clock needs no setting back
                ServerLevel level = player.level();
                Holder<WorldClock> clock = level.dimensionType().defaultClock().orElseThrow();
                s.clockManager().moveToTimeMarker(clock, ClockTimeMarkers.NOON);
                level.updateSkyBrightness();
                return made;
            });
            try {
                // Each check cleans up after itself, and one failing doesn't stop the ones after it
                ClientTests.runAll(List.of(
                        () -> showFrost(ctx, server, connection, stage),
                        () -> showBloodMoon(ctx, server, connection, stage),
                        () -> showAnvil(ctx, server, connection, stage),
                        () -> showCurse(ctx, server, connection, stage),
                        () -> showBedRest(ctx, server, connection, stage, cameraWas),
                        () -> showSlowedView(ctx, server, connection, stage),
                        // Last, and never failing the run: loading a pack this way is the least sure part
                        () -> showPack(ctx, server, connection, stage)));
            } finally {
                // The switches are static, so later client tests in this JVM would see them
                ClientTests.runAll(List.of(
                        () -> server.runOnServer(s -> restore(Settings.get().hardcore, was)),
                        () -> server.runOnServer(s -> Settings.get().resourcePack.reviveTexture = textureWas),
                        () -> ctx.runOnClient(mc -> mc.options.setCameraType(cameraWas)),
                        () -> server.runOnServer(s -> {
                            ServerPlayer player = connection.getServerPlayer();
                            player.setGameMode(stage.modeWas());
                            player.setExperienceLevels(stage.levelsWas());
                        })));
            }
        });
    }

    // Half chill shows the frost overlay; full chill turns the hearts blue
    private static void showFrost(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                  TestDedicatedServerConnection connection, Stage stage) {
        stand(ctx, server, connection, stage, SOUTH, LEVEL);
        server.runOnServer(s -> {
            HardcoreConfig.ColdSection cold = Settings.get().hardcore.cold;
            cold.enabled = true;
            // The test world isn't cold, so with no warming the chill set here stays put
            cold.warmPerSecond = 0.0;
            // No freeze hits, so the hearts stay full for the shot
            cold.freezeDamage = false;
        });
        try {
            setChill(server, stage, 0.5);
            ClientTests.waitOnClient(ctx, LONG_TIMEOUT,
                    mc -> Math.abs(mc.player.getTicksFrozen() - HALF_FROZEN_TICKS) <= TOLERANCE_TICKS,
                    mc -> "the client has " + mc.player.getTicksFrozen() + " frozen ticks at half chill, not about "
                            + HALF_FROZEN_TICKS);
            ctx.waitTicks(SETTLE_TICKS);
            ClientTests.screenshot(ctx, "C-FROST-half");

            setChill(server, stage, 1.0);
            ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.isFullyFrozen(),
                    mc -> "the client is not fully frozen at full chill, with " + mc.player.getTicksFrozen() + " frozen ticks");
            ctx.waitTicks(SETTLE_TICKS);
            ClientTests.screenshot(ctx, "C-FROST-full");
        } finally {
            ClientTests.runAll(List.of(
                    () -> setChill(server, stage, 0.0),
                    () -> server.runOnServer(s -> {
                        Settings.get().hardcore.cold.enabled = false;
                        connection.getServerPlayer().setTicksFrozen(0);
                    }),
                    () -> ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.getTicksFrozen() == 0,
                            mc -> "the client still has " + mc.player.getTicksFrozen() + " frozen ticks after the cold")));
        }
    }

    private static void setChill(TestDedicatedServerContext server, Stage stage, double chill) {
        server.runOnServer(s -> HardcoreStates.update(stage.self(), state -> state.chill = chill));
    }

    // The red boss bar, and the fog and terrain light it darkens, from the same level view as the normal field of view shot
    private static void showBloodMoon(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection, Stage stage) {
        stand(ctx, server, connection, stage, SOUTH, LEVEL);
        server.runOnServer(s -> {
            Settings.get().hardcore.bloodMoon.enabled = true;
            BloodMoon.start(s);
            if (!BloodMoon.active()) {
                throw new AssertionError("no blood moon is up after starting one");
            }
        });
        try {
            ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.gui.hud.getBossOverlay().shouldDarkenScreen(),
                    mc -> "the client has no boss bar that darkens the sky");
            ctx.waitTicks(SETTLE_TICKS);
            ClientTests.screenshot(ctx, "C-BLOOD");
        } finally {
            ClientTests.runAll(List.of(
                    () -> server.runOnServer(s -> {
                        BloodMoon.stop(s);
                        Settings.get().hardcore.bloodMoon.enabled = false;
                    }),
                    () -> ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> !mc.gui.hud.getBossOverlay().shouldDarkenScreen(),
                            mc -> "the client still has the blood moon's boss bar"),
                    // The darkening fades over about 80 ticks, so it can't colour the shots after this one
                    () -> ctx.waitTicks(UNDARKEN_TICKS)));
        }
    }

    // A rename of an item with prior work, which vanilla would price higher, at the flat rename price
    private static void showAnvil(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                  TestDedicatedServerConnection connection, Stage stage) {
        stand(ctx, server, connection, stage, NORTH, LEVEL);
        BlockPos anvil = stage.homeBlock().relative(Direction.NORTH, USE_DISTANCE);
        int price = server.computeOnServer(s -> {
            Settings.get().hardcore.anvil.enabled = true;
            ServerPlayer player = connection.getServerPlayer();
            player.setExperienceLevels(XP_LEVELS);
            player.level().setBlock(anvil, Blocks.ANVIL.defaultBlockState(), Block.UPDATE_ALL);
            return Settings.get().hardcore.anvil.renameCost;
        });
        try {
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                    mc -> mc.level.getBlockState(anvil).getBlock() instanceof AnvilBlock,
                    mc -> "the client has no anvil at " + anvil.toShortString());
            use(ctx, anvil, AnvilBlock.class, "anvil");
            ctx.waitForScreen(AnvilScreen.class);
            server.runOnServer(s -> {
                if (!(connection.getServerPlayer().containerMenu instanceof AnvilMenu menu)) {
                    throw new AssertionError("the server has no anvil menu open for the client");
                }
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.set(DataComponents.REPAIR_COST, PRIOR_WORK);
                menu.getSlot(AnvilMenu.INPUT_SLOT).set(sword);
            });
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                    mc -> mc.player.containerMenu instanceof AnvilMenu menu && menu.getSlot(AnvilMenu.INPUT_SLOT).hasItem(),
                    mc -> "the client's anvil never got the sword");
            // The name box fills with the item's own name; it is cleared and a new one typed, as a player would
            int length = ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(AnvilMenu.INPUT_SLOT).getItem()
                    .getHoverName().getString().length());
            for (int i = 0; i < length; i++) {
                ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE);
            }
            ctx.getInput().typeChars(NEW_NAME);
            connection.waitForServerboundPackets();
            connection.waitForClientboundPackets();
            ClientTests.waitOnClient(ctx, LONG_TIMEOUT,
                    mc -> mc.player.containerMenu instanceof AnvilMenu menu && menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()
                            && menu.getCost() == price,
                    mc -> mc.player.containerMenu instanceof AnvilMenu menu
                            ? "the client's anvil shows a price of " + menu.getCost() + ", not the flat " + price
                            : "the client's anvil menu closed");
            // Off the menu, so no slot is highlighted
            ctx.getInput().setCursorPos(0, 0);
            ctx.waitTicks(SETTLE_TICKS);
            ClientTests.screenshot(ctx, "C-ANVIL");
        } finally {
            ClientTests.runAll(List.of(
                    () -> {
                        if (ctx.computeOnClient(mc -> mc.gui.screen() instanceof AnvilScreen)) {
                            ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
                            ctx.waitForScreen(null);
                            connection.waitForServerboundPackets();
                        }
                    },
                    () -> server.runOnServer(s -> {
                        ServerPlayer player = connection.getServerPlayer();
                        player.closeContainer();
                        // Closing the anvil hands the sword back
                        player.getInventory().clearContent();
                        player.setExperienceLevels(stage.levelsWas());
                        player.level().setBlock(anvil, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        Settings.get().hardcore.anvil.enabled = false;
                    })));
        }
    }

    // One heart lost to the curse leaves nine on the bar
    private static void showCurse(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                  TestDedicatedServerConnection connection, Stage stage) {
        stand(ctx, server, connection, stage, SOUTH, LEVEL);
        server.runOnServer(s -> {
            Settings.get().hardcore.curse.enabled = true;
            Curse.set(stage.self(), LOST_HEARTS);
        });
        try {
            ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.getMaxHealth() == CURSED_MAX_HEALTH,
                    mc -> "the client's maximum health is " + mc.player.getMaxHealth() + ", not " + CURSED_MAX_HEALTH);
            ctx.waitTicks(SETTLE_TICKS);
            ClientTests.screenshot(ctx, "C-HEARTS");
        } finally {
            ClientTests.runAll(List.of(
                    () -> server.runOnServer(s -> {
                        Curse.set(stage.self(), 0);
                        Settings.get().hardcore.curse.enabled = false;
                    }),
                    // The curse comes off at the next tick, and the health doesn't refill by itself
                    () -> ClientTests.waitOnServer(server, LONG_TIMEOUT,
                            s -> connection.getServerPlayer().getMaxHealth() == FULL_HEALTH,
                            s -> "the maximum health is still " + connection.getServerPlayer().getMaxHealth()),
                    () -> server.runOnServer(s -> connection.getServerPlayer().setHealth(FULL_HEALTH)),
                    () -> ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.getHealth() == FULL_HEALTH,
                            mc -> "the client's health is " + mc.player.getHealth() + " after the curse")));
        }
    }

    // A nap at noon, seen from behind so the lying player is in the shot
    private static void showBedRest(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                    TestDedicatedServerConnection connection, Stage stage, CameraType cameraWas) {
        stand(ctx, server, connection, stage, SOUTH, LEVEL);
        BlockPos foot = stage.homeBlock().relative(Direction.SOUTH, USE_DISTANCE);
        BlockPos head = foot.relative(Direction.SOUTH);
        server.runOnServer(s -> {
            Settings.get().hardcore.bedRest.enabled = true;
            if (!Settings.get().hardcore.bedRest.allowDaytime) {
                throw new AssertionError("bed rest's daytime naps are off");
            }
            BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
            ServerLevel level = connection.getServerPlayer().level();
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
            level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
            if (!level.isBrightOutside()) {
                throw new AssertionError("it is dark, so the nap would be an ordinary night's sleep");
            }
        });
        try {
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                    mc -> mc.level.getBlockState(foot).getBlock() instanceof AbstractBedBlock,
                    mc -> "the client has no bed at " + foot.toShortString());
            use(ctx, foot, AbstractBedBlock.class, "bed");
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.isSleeping(),
                    mc -> "the client did not get into the bed at noon");
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            ctx.waitTicks(ASLEEP_TICKS);
            if (!ctx.computeOnClient(mc -> mc.player.isSleeping())) {
                throw new AssertionError("the client was woken up before the shot");
            }
            ClientTests.screenshot(ctx, "C-BED");
        } finally {
            ClientTests.runAll(List.of(
                    () -> ctx.runOnClient(mc -> mc.options.setCameraType(cameraWas)),
                    () -> server.runOnServer(s -> {
                        ServerPlayer player = connection.getServerPlayer();
                        if (player.isSleeping()) {
                            player.stopSleepInBed(true, true);
                        }
                        ServerLevel level = player.level();
                        level.setBlock(head, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        level.setBlock(foot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        Settings.get().hardcore.bedRest.enabled = false;
                    }),
                    () -> ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> !mc.player.isSleeping(),
                            mc -> "the client is still in bed")));
        }
    }

    // The same view with no slowdown and at the cap of half speed, which narrows the field of view
    private static void showSlowedView(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                       TestDedicatedServerConnection connection, Stage stage) {
        stand(ctx, server, connection, stage, SOUTH, LEVEL);
        ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> Math.abs(mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED)
                        - mc.player.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)) < 1.0E-6,
                mc -> "the client is slowed before the slowdown, at speed " + mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED));
        ctx.waitTicks(SETTLE_TICKS);
        ClientTests.screenshot(ctx, "C-FOV-normal");

        UUID self = stage.self();
        Runnable unregister = server.computeOnServer(s -> Slowdowns.registerForTesting("client-fov",
                player -> player.getUUID().equals(self) ? SLOWED : 0.0));
        try {
            ClientTests.waitOnServer(server, LONG_TIMEOUT,
                    s -> Math.abs(Slowdowns.applied(connection.getServerPlayer()) - SLOWED) < CLOSE,
                    s -> "the slowdown is " + Slowdowns.applied(connection.getServerPlayer()) + ", not " + SLOWED);
            ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> Math.abs(mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED)
                            - mc.player.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) * (1.0 - SLOWED)) < 1.0E-6,
                    mc -> "the client's speed is " + mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED) + ", not half");
            ctx.waitTicks(SETTLE_TICKS);
            ClientTests.screenshot(ctx, "C-FOV-slowed");
        } finally {
            ClientTests.runAll(List.of(
                    () -> server.runOnServer(s -> unregister.run()),
                    () -> ClientTests.waitOnServer(server, LONG_TIMEOUT,
                            s -> Slowdowns.applied(connection.getServerPlayer()) == 0.0,
                            s -> "the slowdown is still " + Slowdowns.applied(connection.getServerPlayer()))));
        }
    }

    /**
     * A revive paper drawn with the pack's texture, the pack copied from the repo into the client's resource pack folder
     * and loaded as a player would load it. Any failure here is logged and the run carries on, since it is the one
     * check that leans on the folder layout of the machine it runs on.
     */
    private static void showPack(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                 TestDedicatedServerConnection connection, Stage stage) {
        Path copied = null;
        String[] packId = {null};
        try {
            Path source = findPack();
            Path folder = ctx.computeOnClient(Minecraft::getResourcePackDirectory);
            copied = folder.resolve(PACK_FOLDER);
            copyTree(source, copied);
            packId[0] = ctx.computeOnClient(mc -> {
                PackRepository packs = mc.getResourcePackRepository();
                packs.reload();
                return packs.getAvailableIds().stream().filter(id -> id.endsWith(PACK_FOLDER)).findFirst().orElse(null);
            });
            if (packId[0] == null) {
                throw new AssertionError("the client found no pack in " + copied);
            }
            ctx.runOnClient(mc -> mc.getResourcePackRepository().addPack(packId[0]));
            reloadResources(ctx);
            if (!ctx.computeOnClient(mc -> mc.getResourcePackRepository().getSelectedIds().contains(packId[0]))) {
                throw new AssertionError("the client did not keep the pack " + packId[0] + " selected");
            }

            stand(ctx, server, connection, stage, SOUTH, LEVEL);
            Identifier model = server.computeOnServer(s -> {
                Settings.get().resourcePack.reviveTexture = true;
                ItemStack paper = revivePaper();
                ReviveLook.apply(paper);
                connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, paper);
                return Identifier.parse(Settings.get().resourcePack.reviveModel);
            });
            ClientTests.waitOnClient(ctx, LONG_TIMEOUT, mc -> GraveItems.isReviveHead(mc.player.getMainHandItem())
                            && model.equals(mc.player.getMainHandItem().get(DataComponents.ITEM_MODEL)),
                    mc -> "the client holds " + mc.player.getMainHandItem() + ", not a revive paper with the pack's model");
            ClientTests.waitUntilHandsUp(ctx);
            ClientTests.screenshot(ctx, "C-PACK");
        } catch (RuntimeException | AssertionError | IOException e) {
            Yolt.LOGGER.error("The revive paper pack check could not run; C-PACK has no screenshot", e);
        } finally {
            Path toDelete = copied;
            List<Runnable> steps = new ArrayList<>();
            steps.add(() -> server.runOnServer(s -> {
                connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                Settings.get().resourcePack.reviveTexture = false;
            }));
            if (packId[0] != null) {
                steps.add(() -> {
                    ctx.runOnClient(mc -> mc.getResourcePackRepository().removePack(packId[0]));
                    reloadResources(ctx);
                });
            }
            if (toDelete != null) {
                steps.add(() -> deleteTree(toDelete));
            }
            try {
                ClientTests.runAll(steps);
            } catch (RuntimeException | AssertionError e) {
                Yolt.LOGGER.error("Could not clean up after the revive paper pack check", e);
            }
        }
    }

    // The repo holds the pack; the test client runs in a folder somewhere inside it
    private static Path findPack() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path pack = dir.resolve(PACK_SOURCE);
            if (Files.isRegularFile(pack.resolve("pack.mcmeta"))) {
                return pack;
            }
        }
        throw new AssertionError("no " + PACK_SOURCE + " above " + Path.of("").toAbsolutePath());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            // Deepest first, so each folder is empty by the time it goes
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new AssertionError("could not delete " + root, e);
        }
    }

    // Reloads the client's resources and waits until the loading overlay is gone
    private static void reloadResources(ClientGameTestContext ctx) {
        CompletableFuture<Void> reload = ctx.computeOnClient(mc -> mc.reloadResourcePacks());
        ClientTests.waitOnClient(ctx, RELOAD_TIMEOUT, mc -> reload.isDone() && mc.gui.overlay() == null,
                mc -> "the client's resources never finished reloading");
        if (reload.isCompletedExceptionally()) {
            throw new AssertionError("the client's resource reload failed");
        }
    }

    // Marked as the revive recipe marks it; the look comes from ReviveLook, as for a crafted one
    private static ItemStack revivePaper() {
        ItemStack paper = new ItemStack(Items.PAPER);
        CompoundTag inner = new CompoundTag();
        inner.putBoolean("revive", true);
        CompoundTag root = new CompoundTag();
        root.put("yolt", inner);
        paper.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return paper;
    }

    // Back on the spot the client joined at, looking the given way, with the chunks drawn
    private static void stand(ClientGameTestContext ctx, TestDedicatedServerContext server,
                              TestDedicatedServerConnection connection, Stage stage, float yaw, float pitch) {
        Vec3 at = stage.home();
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(player.level(), at.x, at.y, at.z, Set.of(), yaw, pitch, true);
        });
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.player.position().distanceTo(at) < CLOSE,
                mc -> "the client is at " + mc.player.position() + ", not back at " + at);
        ctx.getInput().lookAt(yaw, pitch);
        connection.waitForClientboundPackets();
        connection.waitForChunksRender();
    }

    // A real right-click on the block
    private static void use(ClientGameTestContext ctx, BlockPos pos, Class<? extends Block> type, String what) {
        ctx.getInput().lookAt(pos);
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT,
                mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                        && type.isInstance(mc.level.getBlockState(hit.getBlockPos()).getBlock()),
                mc -> "the client looks at " + mc.hitResult + ", not the " + what);
        ctx.getInput().pressKey(options -> options.keyUse);
    }

    // The hardcore settings this test changes, to put back at the end
    private static HardcoreConfig.HardcoreSection copy(HardcoreConfig.HardcoreSection live) {
        HardcoreConfig.HardcoreSection saved = new HardcoreConfig.HardcoreSection();
        saved.cold.enabled = live.cold.enabled;
        saved.cold.warmPerSecond = live.cold.warmPerSecond;
        saved.cold.freezeDamage = live.cold.freezeDamage;
        saved.bloodMoon.enabled = live.bloodMoon.enabled;
        saved.anvil.enabled = live.anvil.enabled;
        saved.curse.enabled = live.curse.enabled;
        saved.bedRest.enabled = live.bedRest.enabled;
        return saved;
    }

    private static void restore(HardcoreConfig.HardcoreSection live, HardcoreConfig.HardcoreSection saved) {
        live.cold.enabled = saved.cold.enabled;
        live.cold.warmPerSecond = saved.cold.warmPerSecond;
        live.cold.freezeDamage = saved.cold.freezeDamage;
        live.bloodMoon.enabled = saved.bloodMoon.enabled;
        live.anvil.enabled = saved.anvil.enabled;
        live.curse.enabled = saved.curse.enabled;
        live.bedRest.enabled = saved.bedRest.enabled;
    }
}
