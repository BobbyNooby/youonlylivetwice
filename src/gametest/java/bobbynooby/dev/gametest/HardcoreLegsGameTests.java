package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.gametest.mixin.FoodDataAccessor;
import bobbynooby.dev.hardcore.HardcoreStates;
import bobbynooby.dev.hardcore.Legs;
import bobbynooby.dev.hardcore.Slowdowns;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

// Legs stay on through each test's environment, which puts the switch back when the batch ends. A fall is the damage
// itself, sent straight into the hurt path, so no test depends on how a fake player drops through the air.
public class HardcoreLegsGameTests {
    private static final String LEGS = "you-only-live-twice-gametest:hardcore_legs";
    // A death turns the lockout and graves off, so the dead player is neither kicked nor leaves a grave behind
    private static final String LEGS_DEATH = "you-only-live-twice-gametest:hardcore_legs_death";
    // Weight on as well, and heavy landings
    private static final String LEGS_HEAVY = "you-only-live-twice-gametest:hardcore_legs_heavy";
    // A body's fall breaks the owner's legs too, which ships off
    private static final String LEGS_BODIES = "you-only-live-twice-gametest:hardcore_legs_bodies";
    // A batch of its own: it changes the switch through the config file
    private static final String LEGS_SWITCH = "you-only-live-twice-gametest:hardcore_legs_switch";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    private static final double CLOSE = 1e-9;
    // Weight weighs again every 10 ticks; raise it with that interval
    private static final int WEIGHED = 12;

    // 9 blocks less the 3 safe ones is vanilla's 6 fall damage, all of it left with no armour
    @GameTest(environment = LEGS, maxTicks = 20)
    public void legs1NineBlockFallBreaksLegs(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Faller");
        Runnable cleanUp = BodyTestKit.once(() -> leave(player));
        boolean waiting = false;
        try {
            player.causeFallDamage(9.0, 1.0F, helper.getLevel().damageSources().fall());
            helper.assertTrue(Legs.broken(player), "a 9-block fall left the legs whole");
            helper.assertTrue(actionBars(player).contains("Your legs broke."), "action bar: " + actionBars(player));
            helper.runAfterDelay(2, () -> {
                try {
                    assertSlow(helper, player, 0.2, "with broken legs");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Feather Falling IV takes 12 of the 25 protection points: 6 × 13/25 = 3.12 is left
    @GameTest(environment = LEGS)
    public void legs1FeatherFallingFourSavesThem(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Feathered");
        try {
            ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
            boots.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.FEATHER_FALLING), 4);
            player.setItemSlot(EquipmentSlot.FEET, boots);
            player.causeFallDamage(9.0, 1.0F, helper.getLevel().damageSources().fall());
            helper.assertTrue(player.getHealth() < player.getMaxHealth(), "the fall did no damage at all");
            helper.assertFalse(Legs.broken(player), "the fall broke the legs through Feather Falling IV");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = LEGS)
    public void legs2EnderPearlNeverBreaksLegs(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Pearler");
        try {
            player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().enderPearl(), 10.0F);
            helper.assertTrue(player.getHealth() < player.getMaxHealth(), "the pearl did no damage");
            helper.assertFalse(Legs.broken(player), "an ender pearl broke the legs");
        } finally {
            leave(player);
        }
        helper.succeed();
    }

    // Food 20 with no saturation heals a point every 80 ticks, so 200 ticks would give two without the health rule.
    // Health is put back to 19 each tick, since vanilla's own regen would fill it.
    @GameTest(environment = LEGS, maxTicks = 320)
    public void legs3HealingWaitsForFullHealth(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Mender");
        Runnable cleanUp = BodyTestKit.once(() -> leave(player));
        AtomicBoolean holding = new AtomicBoolean(true);
        boolean waiting = false;
        try {
            Legs.set(player.getUUID(), true);
            feed(player, 20, 0.0F);
            player.setHealth(19.0F);
            helper.onEachTick(() -> {
                if (holding.get()) {
                    player.setHealth(19.0F);
                }
            });
            helper.runAfterDelay(200, () -> {
                try {
                    assertPoints(helper, player, 0.0, "after 200 ticks below full health");
                    holding.set(false);
                    feed(player, 20, 0.0F);
                    player.setHealth(player.getMaxHealth());
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            // One point by 80 ticks, and the next not before 160
            helper.runAfterDelay(285, () -> {
                try {
                    assertPoints(helper, player, 1.0, "80 ticks after health was full");
                    float spent = ((FoodDataAccessor) player.getFoodData()).yoltExhaustion();
                    helper.assertTrue(Math.abs(spent - 3.0F) < 1e-5, "the point cost " + spent + " exhaustion, not 3.0");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Food 10 heals nothing by itself, so the point is the effect's, and it costs no food
    @GameTest(environment = LEGS, maxTicks = 80)
    public void legs3RegenerationHealsLegs(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Regenerator");
        Runnable cleanUp = BodyTestKit.once(() -> leave(player));
        boolean waiting = false;
        try {
            Legs.set(player.getUUID(), true);
            feed(player, 10, 0.0F);
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 1200, 0));
            helper.runAfterDelay(55, () -> {
                try {
                    assertPoints(helper, player, 1.0, "55 ticks into Regeneration I");
                    float spent = ((FoodDataAccessor) player.getFoodData()).yoltExhaustion();
                    helper.assertTrue(Math.abs(spent) < 1e-5, "Regeneration's point cost " + spent + " exhaustion");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // kill() can't be stopped by a totem, so the same player is saved once and then dies
    @GameTest(environment = LEGS_DEATH)
    public void legs4DeathClearsATotemDoesNot(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Survivor");
        UUID uuid = player.getUUID();
        ServerPlayer back = null;
        try {
            Legs.set(uuid, true);
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.setHealth(2.0F);
            player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10.0F);
            helper.assertFalse(player.isDeadOrDying(), "the totem did not save the player");
            helper.assertTrue(Legs.broken(player), "the totem cleared the broken legs");

            GraveDeathGameTests.respawnBesideTest(helper, player);
            player.kill(player.level());
            helper.assertTrue(player.isDeadOrDying(), "the player did not die");
            back = TestPlayers.respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertFalse(HardcoreStates.read(uuid).legsBroken, "the death left the stored legs broken");
            helper.assertFalse(Legs.broken(back), "the respawned player's legs are broken");
        } finally {
            Legs.set(uuid, false);
            leave(back != null ? back : player);
        }
        helper.succeed();
    }

    // 27 + 27 + 1 + 1 is a full load, which breaks at 4 with heavy landings and at the plain 6 without. Weight keeps
    // what it weighed at the join for a few ticks, so the falls wait for it to weigh the load.
    @GameTest(environment = LEGS_HEAVY, maxTicks = 30)
    public void legs5HeavyLandings(GameTestHelper helper) {
        ServerPlayer heavy = join(helper, "Lander");
        ServerPlayer plain = join(helper, "Plainlander", AT.offset(1, 0, 0));
        Runnable cleanUp = BodyTestKit.once(() -> {
            for (ServerPlayer player : List.of(heavy, plain)) {
                player.getInventory().clearContent();
                leave(player);
            }
        });
        boolean waiting = false;
        try {
            for (ServerPlayer player : List.of(heavy, plain)) {
                fill(player, Items.IRON_BLOCK, 2);
                fill(player, Items.DIRT, 2);
            }
            helper.runAfterDelay(WEIGHED, () -> {
                HardcoreConfig.LegsSection legs = Settings.get().hardcore.legs;
                boolean before = legs.heavyLandings;
                try {
                    helper.assertTrue(legs.heavyLandings, "the environment left heavy landings off");
                    heavy.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fall(), 4.0F);
                    helper.assertTrue(Legs.broken(heavy), "4 fall damage at a full load left the legs whole");

                    // Off for this tick only
                    legs.heavyLandings = false;
                    plain.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fall(), 4.0F);
                    helper.assertTrue(plain.getHealth() < plain.getMaxHealth(), "the fall did no damage");
                    helper.assertFalse(Legs.broken(plain), "4 fall damage broke the legs with heavy landings off");
                } finally {
                    legs.heavyLandings = before;
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // With bodiesCanBreakLegs on, the body is its owner: the fall is written to the owner's stored state, and they come
    // back with it. Both kinds of body.
    @GameTest(environment = LEGS_BODIES)
    public void legs6ABodyCanBreakItsLegs(GameTestHelper helper) {
        bodyFall(helper, "Sleeper", false, true);
        bodyFall(helper, "SleeperP", true, true);
        helper.succeed();
    }

    // By default a body's fall only costs it health: nothing about a logged-out player heals, so legs broken then would
    // stay broken until they came back
    @GameTest(environment = LEGS)
    public void legs6ABodysFallLeavesTheLegsWholeByDefault(GameTestHelper helper) {
        helper.assertFalse(Settings.get().hardcore.legs.bodiesCanBreakLegs, "bodiesCanBreakLegs is on");
        bodyFall(helper, "Dozer", false, false);
        bodyFall(helper, "DozerP", true, false);
        helper.succeed();
    }

    // A 6 fall on the player's body, a mannequin or a player-type one, then the owner logs back in
    private static void bodyFall(GameTestHelper helper, String name, boolean playerBody, boolean breaks) {
        ServerPlayer player = join(helper, name);
        UUID owner = player.getUUID();
        GameProfile profile = player.getGameProfile();
        YoltConfig.BodiesSection before = playerBody ? BodyTestKit.usePlayerBodies() : BodyTestKit.useDefaultBodies();
        String kind = playerBody ? "player body" : "mannequin";
        try {
            LivingEntity body = playerBody ? BodyTestKit.leavePlayerBody(helper, player) : BodyTestKit.leaveBody(helper, player);
            float health = body.getHealth();
            body.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fall(), 6.0F);
            helper.assertTrue(body.getHealth() < health, "the " + kind + " took no damage from the fall");
            helper.assertValueEqual(HardcoreStates.read(owner).legsBroken, breaks, "the owner's stored legs broken after the " + kind + "'s fall");

            ServerPlayer back = TestPlayers.rejoin(helper, profile, GameType.SURVIVAL, AT);
            helper.assertValueEqual(Legs.broken(back), breaks, "the owner's legs broken after coming back from a " + kind);
            assertSlow(helper, back, breaks ? 0.2 : 0.0, "after coming back from a " + kind);
        } finally {
            Legs.set(owner, false);
            BodyTestKit.cleanUp(helper, owner, before);
        }
    }

    // /yolt config writes the file and swaps the config object; the test puts the file back and reloads it at its end
    @GameTest(environment = LEGS_SWITCH, maxTicks = 40)
    public void off2SwitchingLegsOffLiveKeepsTheState(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        byte[] fileBefore = Files.readAllBytes(Settings.path());
        ServerPlayer player = join(helper, "Switcher");
        UUID uuid = player.getUUID();
        Runnable cleanUp = BodyTestKit.once(() -> {
            Legs.set(uuid, false);
            leave(player);
            try {
                ConfigCommandGameTests.restore(server, fileBefore);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        boolean waiting = false;
        try {
            Legs.set(uuid, true);
            helper.runAfterDelay(2, () -> {
                try {
                    assertSlow(helper, player, 0.2, "with broken legs");
                    CommandGameTests.run(server, "yolt config hardcore.legs.enabled false");
                    helper.assertFalse(Settings.get().hardcore.legs.enabled, "the command left legs on");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(4, () -> {
                try {
                    assertSlow(helper, player, 0.0, "with legs switched off");
                    helper.assertTrue(HardcoreStates.read(uuid).legsBroken, "switching legs off cleared the stored state");
                    CommandGameTests.run(server, "yolt config hardcore.legs.enabled true");
                    helper.assertTrue(Settings.get().hardcore.legs.enabled, "the command left legs off");
                } catch (RuntimeException e) {
                    cleanUp.run();
                    throw e;
                }
            });
            helper.runAfterDelay(6, () -> {
                try {
                    assertSlow(helper, player, 0.2, "with legs switched back on");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Loaded, so the player can be hurt, which vanilla holds off until a client says the world is in
    private static ServerPlayer join(GameTestHelper helper, String name) {
        return join(helper, name, AT);
    }

    private static ServerPlayer join(GameTestHelper helper, String name, BlockPos at) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, at);
        TestPlayers.markLoaded(player);
        return player;
    }

    // By uuid: a player who left already, or respawned as another object, is not left twice
    private static void leave(ServerPlayer player) {
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(player.getUUID());
        if (online != null) {
            TestPlayers.leave(online);
        }
    }

    private static void feed(ServerPlayer player, int food, float saturation) {
        FoodData data = player.getFoodData();
        data.setFoodLevel(food);
        data.setSaturation(saturation);
        ((FoodDataAccessor) data).yoltSetExhaustion(0.0F);
    }

    private static List<String> actionBars(ServerPlayer player) {
        return TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket chat && chat.overlay())
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                .toList();
    }

    private static void assertPoints(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double points = HardcoreStates.read(player.getUUID()).legHealPoints;
        helper.assertTrue(HardcoreStates.read(player.getUUID()).legsBroken, "the legs healed " + when);
        helper.assertTrue(Math.abs(points - expected) < CLOSE, points + " heal points " + when + ", not " + expected);
    }

    private static void assertSlow(GameTestHelper helper, ServerPlayer player, double expected, String when) {
        double applied = Slowdowns.applied(player);
        helper.assertTrue(Math.abs(applied - expected) < CLOSE, "slowdown " + applied + " " + when + ", not " + expected);
    }

    // Full stacks into the first empty slots
    private static void fill(ServerPlayer player, Item item, int stacks) {
        for (int i = 0; i < stacks; i++) {
            player.getInventory().add(new ItemStack(item, 64));
        }
    }
}
