package bobbynooby.dev.hardcore;

import bobbynooby.dev.elytra.YoltElytra;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;

import java.util.Objects;

import static bobbynooby.dev.hardcore.HardcoreTestKit.assertClose;

/**
 * YOLT Anvils, YOLT Mending and YOLT Elytra at work inside this mod's jar, with no YOLT on the server: each prices,
 * repairs or glides by this mod's hardcore block. HardcoreAloneGameTests checks the settings each one is handed; these
 * check the work, each with a value the tiny mod's own defaults don't have, so it can only have come from here. The
 * anvil and Mending tests swap the settings in and out within one tick; the glide waits for the elytra mod's own tick,
 * in a batch of its own.
 */
public class HardcoreTinyModsAloneGameTests {
    private static final String GLIDE = "yolt-hardcore-gametest:glide_alone";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    // Vanilla prices a rename at 1 plus the item's prior work
    private static final int PRIOR_WORK = 10;
    private static final int DAMAGE = 100;

    // A rename of a sword with prior work: the flat rename price as shipped, then the price the hardcore block sets, and
    // vanilla's with the hardcore block's switch off
    @GameTest
    public void theAnvilPricesByThisModsSettings(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "TinyAnvil", GameType.SURVIVAL, AT);
            helper.assertValueEqual(renamePrice(player), 1, "the rename price as shipped");
            YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.anvil.renameCost = 3));
            helper.assertValueEqual(renamePrice(player), 3, "the rename price at a renameCost of 3");
            YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.anvil.enabled = false));
            helper.assertValueEqual(renamePrice(player), 1 + PRIOR_WORK, "the rename price with the flat price off");
        } finally {
            try {
                TestPlayers.leave(player);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // Five XP points at the shipped 0.4 a point repair 2, at the hardcore block's 1.0 they repair 5, and with its switch
    // off one point repairs vanilla's 2
    @GameTest
    public void mendingRepairsByThisModsSettings(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        ServerPlayer player = null;
        try {
            player = TestPlayers.join(helper, "TinyMending", GameType.SURVIVAL, AT);
            helper.assertValueEqual(repaired(helper, player, 5), 2, "what 5 XP repaired as shipped");
            YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.mending.durabilityPerXp = 1.0));
            helper.assertValueEqual(repaired(helper, player, 5), 5, "what 5 XP repaired at 1.0 a point");
            YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.mending.enabled = false));
            helper.assertValueEqual(repaired(helper, player, 1), 2, "what 1 XP repaired with the nerf off");
        } finally {
            try {
                TestPlayers.leave(player);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The elytra mod ticks every player on the server itself. Halving gravity is the hardcore block's 0.5, not the
    // tiny mod's own 0.6; with the switch off the next tick takes it off while the glide goes on.
    @GameTest(environment = GLIDE, maxTicks = 20)
    public void theGlideFallsByThisModsGravity(GameTestHelper helper) {
        Held held = new Held(helper, c -> {
            c.elytra.enabled = true;
            c.elytra.glideGravityMultiplier = 0.5;
        });
        try {
            ServerPlayer player = held.join("TinyGlider", GameType.SURVIVAL, AT);
            held.onCleanUp(() -> {
                player.stopFallFlying();
                player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            });
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            player.startFallFlying();
            helper.assertTrue(gravityModifier(player) == null, "the glide's gravity is on before the elytra mod's tick");
            helper.runAfterDelay(2, held.step(() -> {
                AttributeModifier modifier = gravityModifier(player);
                helper.assertTrue(modifier != null, "no glide gravity on a glider");
                assertClose(helper, modifier.amount(), -0.5, "the glide's gravity modifier");
                helper.assertValueEqual(modifier.operation(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                        "the glide's gravity modifier's operation");
                AttributeInstance gravity = gravity(player);
                assertClose(helper, gravity.getValue(), gravity.getBaseValue() * 0.5, "the glider's gravity");
                // The defaults, where the elytra nerf ships off
                YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
            }));
            helper.runAfterDelay(4, held.last(() -> {
                helper.assertTrue(player.isFallFlying(), "the glide ended");
                helper.assertTrue(gravityModifier(player) == null, "the glide's gravity is on with the elytra nerf off");
            }));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    // A new anvil each time, as opening one makes, and the price it shows for a new name
    private static int renamePrice(ServerPlayer player) {
        AnvilMenu menu = new AnvilMenu(1, player.getInventory(), ContainerLevelAccess.NULL);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.REPAIR_COST, PRIOR_WORK);
        menu.getSlot(AnvilMenu.INPUT_SLOT).set(sword);
        menu.setItemName("Frostbite");
        return menu.getCost();
    }

    // A worn pickaxe with Mending in the main hand, then an orb's touch, which runs vanilla's whole pickup
    private static int repaired(GameTestHelper helper, ServerPlayer player, int xp) {
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
        pickaxe.setDamageValue(DAMAGE);
        player.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
        ExperienceOrb orb = new ExperienceOrb(player.level(), player.getX(), player.getY(), player.getZ(), xp);
        player.takeXpDelay = 0;
        orb.playerTouch(player);
        return DAMAGE - player.getItemBySlot(EquipmentSlot.MAINHAND).getDamageValue();
    }

    private static AttributeInstance gravity(ServerPlayer player) {
        return Objects.requireNonNull(player.getAttribute(Attributes.GRAVITY), "a player without a gravity attribute");
    }

    private static AttributeModifier gravityModifier(ServerPlayer player) {
        return gravity(player).getModifier(YoltElytra.GRAVITY_MODIFIER);
    }
}
