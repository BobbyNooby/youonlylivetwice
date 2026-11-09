package bobbynooby.dev.gametest;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.revive.Revive;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Owners are fresh random uuids, so a death recorded here never locks out anyone else
public class ReviveGameTests {
    @GameTest
    public void recipeTransmutesGraveHeadToReviveHead(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Crafted", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            CraftingInput input = grid(GraveItems.graveHead(GraveItems.profileOf(player)), new ItemStack(Items.NETHER_STAR));
            Optional<RecipeHolder<CraftingRecipe>> recipe = findRecipe(helper, input);
            helper.assertTrue(recipe.isPresent(), "no recipe for a grave head and a nether star");
            helper.assertValueEqual(recipe.get().id().identifier(), YouOnlyLiveTwice.id("revive"), "recipe id");

            ItemStack result = recipe.get().value().assemble(input);
            helper.assertTrue(result.is(Items.PLAYER_HEAD), "result is not a player head");
            helper.assertValueEqual(result.getCount(), 1, "result count");
            helper.assertTrue(GraveItems.isReviveHead(result), "result is not a revive head");
            helper.assertFalse(GraveItems.isGraveHead(result), "result kept the grave head marker");
            helper.assertTrue(GraveItems.ownerId(result).filter(player.getUUID()::equals).isPresent(), "result lost the owner");
            helper.assertTrue(Boolean.TRUE.equals(result.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)), "result has no glint");
            helper.assertValueEqual(result.get(DataComponents.RARITY), Rarity.EPIC, "rarity");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest
    public void plainHeadDoesNotMatchRecipe(GameTestHelper helper) {
        CraftingInput input = grid(new ItemStack(Items.PLAYER_HEAD), new ItemStack(Items.NETHER_STAR));
        helper.assertTrue(findRecipe(helper, input).isEmpty(), "a plain head and a nether star made something");
        helper.succeed();
    }

    @GameTest
    public void reviveEndsLockoutAndUsesOneHead(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        DeathLockout.recordDeath(owner, "Ghost");
        helper.assertTrue(DeathLockout.refusal(owner) != null, "owner is not locked out to begin with");
        ServerPlayer user = TestPlayers.join(helper, "Reviver", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack heads = reviveHead(staticProfile(owner), 2);
            helper.assertValueEqual(Revive.tryUse(user, heads), Revive.Outcome.REVIVED, "outcome");
            helper.assertValueEqual(heads.getCount(), 1, "heads left");
            helper.assertTrue(DeathLockout.refusal(owner) == null, "owner is still locked out");
        } finally {
            TestPlayers.leave(user);
        }
        helper.succeed();
    }

    @GameTest
    public void reviveNotUsedWhenOwnerNotDead(GameTestHelper helper) {
        ServerPlayer user = TestPlayers.join(helper, "Early", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack head = reviveHead(staticProfile(UUID.randomUUID()), 1);
            helper.assertValueEqual(Revive.tryUse(user, head), Revive.Outcome.NOT_DEAD, "outcome");
            helper.assertValueEqual(head.getCount(), 1, "heads left");
        } finally {
            TestPlayers.leave(user);
        }
        helper.succeed();
    }

    // A name alone resolves to an offline id, so only a missing profile or one with neither name nor id has no owner
    @GameTest
    public void reviveWithoutOwnerIdRefused(GameTestHelper helper) {
        ServerPlayer user = TestPlayers.join(helper, "Nobody", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack noProfile = reviveHead(null, 1);
            helper.assertValueEqual(Revive.tryUse(user, noProfile), Revive.Outcome.NO_OWNER, "outcome without a profile");
            helper.assertValueEqual(noProfile.getCount(), 1, "heads left without a profile");

            ItemStack emptyProfile = reviveHead(ResolvableProfile.Static.EMPTY, 1);
            helper.assertValueEqual(Revive.tryUse(user, emptyProfile), Revive.Outcome.NO_OWNER, "outcome with an empty profile");
            helper.assertValueEqual(emptyProfile.getCount(), 1, "heads left with an empty profile");
        } finally {
            TestPlayers.leave(user);
        }
        helper.succeed();
    }

    // Flips a live switch, so it restores it in the same tick
    @GameTest
    public void reviveOffKeepsHeadAndLockout(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        DeathLockout.recordDeath(owner, "Ghost");
        ServerPlayer user = TestPlayers.join(helper, "Switched", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().revive.enabled;
        try {
            Settings.get().revive.enabled = false;
            ItemStack head = reviveHead(staticProfile(owner), 1);
            helper.assertValueEqual(Revive.tryUse(user, head), Revive.Outcome.DISABLED, "outcome");
            helper.assertValueEqual(head.getCount(), 1, "heads left");
            helper.assertTrue(DeathLockout.refusal(owner) != null, "owner was revived with revives off");
        } finally {
            Settings.get().revive.enabled = before;
            TestPlayers.leave(user);
        }
        helper.succeed();
    }

    // The plain head first shows this spot takes a head at all, so the marked heads can't pass by accident
    @GameTest
    public void markedHeadsCannotBePlaced(GameTestHelper helper) {
        BlockPos plainStone = new BlockPos(3, 1, 3);
        BlockPos markedStone = new BlockPos(5, 1, 3);
        helper.setBlock(plainStone, Blocks.STONE);
        helper.setBlock(markedStone, Blocks.STONE);
        helper.setBlock(plainStone.above(), Blocks.AIR);
        helper.setBlock(markedStone.above(), Blocks.AIR);
        ServerPlayer player = TestPlayers.join(helper, "Placer", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            useOnTop(helper, player, new ItemStack(Items.PLAYER_HEAD), plainStone);
            helper.assertBlockPresent(Blocks.PLAYER_HEAD, plainStone.above());

            ResolvableProfile profile = staticProfile(UUID.randomUUID());
            for (ItemStack marked : List.of(GraveItems.graveHead(profile), reviveHead(profile, 1))) {
                InteractionResult result = useOnTop(helper, player, marked, markedStone);
                helper.assertTrue(result instanceof InteractionResult.Fail, "using a marked head did not fail: " + result);
                helper.assertBlockPresent(Blocks.AIR, markedStone.above());
                helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "marked heads in hand");
            }
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The two stacks go in the top-left corner of an otherwise empty 3x3 grid
    private static CraftingInput grid(ItemStack first, ItemStack second) {
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        items.set(0, first);
        items.set(1, second);
        return CraftingInput.of(3, 3, items);
    }

    private static Optional<RecipeHolder<CraftingRecipe>> findRecipe(GameTestHelper helper, CraftingInput input) {
        ServerLevel level = helper.getLevel();
        return level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
    }

    // Built the way the recipe builds it, without the lore and looks the tests don't need
    private static ItemStack reviveHead(ResolvableProfile profile, int count) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD, count);
        if (profile != null) {
            stack.set(DataComponents.PROFILE, profile);
        }
        CompoundTag inner = new CompoundTag();
        inner.putBoolean("revive", true);
        CompoundTag root = new CompoundTag();
        root.put("yolt", inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return stack;
    }

    // Static, so nothing tries to look the owner up
    private static ResolvableProfile staticProfile(UUID owner) {
        return ResolvableProfile.createResolved(new GameProfile(owner, "Ghost"));
    }

    private static InteractionResult useOnTop(GameTestHelper helper, ServerPlayer player, ItemStack stack, BlockPos relativePos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos pos = helper.absolutePos(relativePos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), Direction.UP, pos, false);
        return player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.MAIN_HAND, hit);
    }
}
