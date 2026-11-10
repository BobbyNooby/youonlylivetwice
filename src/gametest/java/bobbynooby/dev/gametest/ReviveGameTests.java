package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathStore;
import bobbynooby.dev.revive.Revive;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.CraftingMenu;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

// Owners are fresh random uuids, so a death recorded here never locks out anyone else
public class ReviveGameTests {
    @GameTest
    public void recipeTransmutesGraveHeadToReviveHead(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Crafted", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            CraftingInput input = grid(GraveItems.graveHead(Profiles.of(player)), new ItemStack(Items.NETHER_STAR));
            Optional<RecipeHolder<CraftingRecipe>> recipe = findRecipe(helper, input);
            helper.assertTrue(recipe.isPresent(), "no recipe for a grave head and a nether star");
            helper.assertValueEqual(recipe.get().id().identifier(), Yolt.id("revive"), "recipe id");

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
        helper.assertTrue(DeathLockout.refusal(owner, "Test") != null, "owner is not locked out to begin with");
        ServerPlayer user = TestPlayers.join(helper, "Reviver", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack heads = reviveHead(staticProfile(owner), 2);
            helper.assertValueEqual(Revive.tryUse(user, heads), Revive.Outcome.REVIVED, "outcome");
            helper.assertValueEqual(heads.getCount(), 1, "heads left");
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "owner is still locked out");
        } finally {
            TestPlayers.leave(user);
        }
        helper.succeed();
    }

    // Creative players use up none of their items, revive heads included
    @GameTest
    public void creativeReviveKeepsHead(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        DeathLockout.recordDeath(owner, "Ghost");
        helper.assertTrue(DeathLockout.refusal(owner, "Test") != null, "owner is not locked out to begin with");
        ServerPlayer user = TestPlayers.join(helper, "CreativeReviver", GameType.CREATIVE, new BlockPos(1, 1, 1));
        try {
            ItemStack heads = reviveHead(staticProfile(owner), 2);
            helper.assertValueEqual(Revive.tryUse(user, heads), Revive.Outcome.REVIVED, "outcome");
            helper.assertValueEqual(heads.getCount(), 2, "heads left");
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "owner is still locked out");
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

    // The reply depends on the lockout switch, which is flipped and put back inside this test
    @GameTest
    public void notDeadReplyFollowsLockoutSwitch(GameTestHelper helper) {
        TestPlayers.Recording user = TestPlayers.joinRecording(helper, "Asker", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        boolean before = Settings.get().deathLockout.enabled;
        try {
            ItemStack head = reviveHead(staticProfile(UUID.randomUUID()), 1);
            helper.assertValueEqual(Revive.tryUse(user, head), Revive.Outcome.NOT_DEAD, "outcome with the lockout on");
            helper.assertValueEqual(user.messages.get(user.messages.size() - 1).getString(), "Ghost is not dead.", "reply with the lockout on");
            Settings.get().deathLockout.enabled = false;
            helper.assertValueEqual(Revive.tryUse(user, head), Revive.Outcome.NOT_DEAD, "outcome with the lockout off");
            helper.assertValueEqual(user.messages.get(user.messages.size() - 1).getString(), "Ghost is not locked out.", "reply with the lockout off");
        } finally {
            Settings.get().deathLockout.enabled = before;
            TestPlayers.leave(user);
        }
        helper.succeed();
    }

    // A name alone would get an id made up from the name, which isn't the player's id on an online-mode server
    @GameTest
    public void reviveWithoutOwnerIdRefused(GameTestHelper helper) {
        Map<String, ItemStack> heads = new LinkedHashMap<>();
        heads.put("no profile", reviveHead(null, 1));
        heads.put("a name only", reviveHead(ResolvableProfile.createUnresolved("SomeName"), 1));
        heads.put("neither name nor id", reviveHead(ResolvableProfile.Static.EMPTY, 1));
        heads.put("the nil id", reviveHead(staticProfile(Util.NIL_UUID), 1));
        ServerPlayer user = TestPlayers.join(helper, "Nobody", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            heads.forEach((label, head) -> {
                helper.assertValueEqual(Revive.tryUse(user, head), Revive.Outcome.NO_OWNER, "outcome with " + label);
                helper.assertValueEqual(head.getCount(), 1, "heads left with " + label);
            });
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
            helper.assertTrue(DeathLockout.refusal(owner, "Test") != null, "owner was revived with revives off");
        } finally {
            Settings.get().revive.enabled = before;
            TestPlayers.leave(user);
        }
        helper.succeed();
    }

    // The plain head first shows this spot takes a head at all, so the marked heads can't pass by accident.
    // Sneaking skips the crafting table's own use, so that click goes straight to placing.
    @GameTest
    public void markedHeadsCannotBePlaced(GameTestHelper helper) {
        BlockPos plainStone = new BlockPos(3, 1, 3);
        BlockPos markedStone = new BlockPos(5, 1, 3);
        BlockPos table = new BlockPos(5, 1, 5);
        helper.setBlock(plainStone, Blocks.STONE);
        helper.setBlock(markedStone, Blocks.STONE);
        helper.setBlock(table, Blocks.CRAFTING_TABLE);
        for (BlockPos below : List.of(plainStone, markedStone, table)) {
            helper.setBlock(below.above(), Blocks.AIR);
        }
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

                player.setShiftKeyDown(true);
                try {
                    result = useOnTop(helper, player, marked, table);
                } finally {
                    player.setShiftKeyDown(false);
                }
                helper.assertTrue(result instanceof InteractionResult.Fail, "sneak-using a marked head did not fail: " + result);
                helper.assertBlockPresent(Blocks.AIR, table.above());
                helper.assertFalse(player.containerMenu instanceof CraftingMenu, "sneaking opened the crafting table");
                helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "marked heads in hand after sneaking");
            }
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Only placing is refused; the head in hand must not stop the table it is crafted on from opening
    @GameTest
    public void graveHeadStillOpensCraftingTable(GameTestHelper helper) {
        BlockPos table = new BlockPos(3, 1, 3);
        helper.setBlock(table, Blocks.CRAFTING_TABLE);
        helper.setBlock(table.above(), Blocks.AIR);
        ServerPlayer player = TestPlayers.join(helper, "Crafter", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack head = GraveItems.graveHead(staticProfile(UUID.randomUUID()));
            InteractionResult result = useOnTop(helper, player, head, table);
            helper.assertTrue(result instanceof InteractionResult.Success, "using the crafting table did not succeed: " + result);
            helper.assertTrue(player.containerMenu instanceof CraftingMenu, "crafting table did not open");
            helper.assertBlockPresent(Blocks.AIR, table.above());
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "grave heads in hand");
            player.closeContainer();
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // What a right-click in the air runs on the server
    @GameTest
    public void reviveInAirThroughUseItem(GameTestHelper helper) {
        UUID owner = lockedOut(helper);
        TestPlayers.Recording user = TestPlayers.joinRecording(helper, "AirReviver", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack heads = reviveHead(staticProfile(owner), 2);
            user.setItemInHand(InteractionHand.MAIN_HAND, heads);
            int mark = user.messages.size();
            InteractionResult result = user.gameMode.useItem(user, user.level(), heads, InteractionHand.MAIN_HAND);
            helper.assertTrue(result instanceof InteractionResult.Success, "using a revive head in the air did not succeed: " + result);
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "owner is still locked out");
            helper.assertValueEqual(user.getMainHandItem().getCount(), 1, "heads left");
            helper.assertValueEqual(said(user, mark), List.of("Ghost can come back now."), "replies");
        } finally {
            TestPlayers.leave(user);
            DeathLockout.revive(owner, "Test", "console");
        }
        helper.succeed();
    }

    // What a right-click on a block runs on the server. The click reaches the revive through the use-block hook, and
    // the head is neither placed nor kept.
    @GameTest
    public void reviveOnBlockThroughUseItemOn(GameTestHelper helper) {
        UUID owner = lockedOut(helper);
        BlockPos stone = new BlockPos(3, 1, 3);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(stone.above(), Blocks.AIR);
        TestPlayers.Recording user = TestPlayers.joinRecording(helper, "BlockReviver", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            int mark = user.messages.size();
            InteractionResult result = useOnTop(helper, user, reviveHead(staticProfile(owner), 2), stone);
            helper.assertTrue(result instanceof InteractionResult.Fail, "using a revive head on a block did not fail: " + result);
            helper.assertBlockPresent(Blocks.AIR, stone.above());
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "owner is still locked out");
            helper.assertValueEqual(user.getMainHandItem().getCount(), 1, "heads left");
            helper.assertValueEqual(said(user, mark), List.of("Ghost can come back now."), "replies");
        } finally {
            TestPlayers.leave(user);
            DeathLockout.revive(owner, "Test", "console");
        }
        helper.succeed();
    }

    // An adventure-mode client can't place the head, so one click on a block sends a use on the block and then a use
    // in the air, both handled in the same tick. Two heads, so the hand still holds one when the use in the air comes.
    @GameTest
    public void reviveInAdventureMessagesOnce(GameTestHelper helper) {
        UUID owner = lockedOut(helper);
        BlockPos stone = new BlockPos(3, 1, 3);
        helper.setBlock(stone, Blocks.STONE);
        TestPlayers.Recording user = TestPlayers.joinRecording(helper, "Adventurer", GameType.ADVENTURE, new BlockPos(1, 1, 1));
        try {
            ItemStack heads = reviveHead(staticProfile(owner), 2);
            int mark = user.messages.size();
            useOnTop(helper, user, heads, stone);
            user.gameMode.useItem(user, user.level(), heads, InteractionHand.MAIN_HAND);
            helper.assertTrue(DeathLockout.refusal(owner, "Test") == null, "owner is still locked out");
            helper.assertValueEqual(user.getMainHandItem().getCount(), 1, "heads left");
            helper.assertValueEqual(said(user, mark), List.of("Ghost can come back now."), "replies to one click");
        } finally {
            TestPlayers.leave(user);
            DeathLockout.revive(owner, "Test", "console");
        }
        helper.succeed();
    }

    // Two owners, so a use that took the off hand's head as well would revive the second one too
    @GameTest
    public void reviveHeadsInBothHandsUseOne(GameTestHelper helper) {
        UUID mainOwner = lockedOut(helper);
        UUID offOwner = lockedOut(helper);
        ServerPlayer user = TestPlayers.join(helper, "TwoHands", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack main = reviveHead(staticProfile(mainOwner), 1);
            user.setItemInHand(InteractionHand.MAIN_HAND, main);
            user.setItemInHand(InteractionHand.OFF_HAND, reviveHead(staticProfile(offOwner), 1));
            user.gameMode.useItem(user, user.level(), main, InteractionHand.MAIN_HAND);
            helper.assertTrue(user.getMainHandItem().isEmpty(), "the main hand's head was not used");
            helper.assertValueEqual(user.getOffhandItem().getCount(), 1, "heads left in the off hand");
            helper.assertTrue(DeathLockout.refusal(mainOwner, "Test") == null, "the main hand's owner is still locked out");
            helper.assertTrue(DeathLockout.refusal(offOwner, "Test") != null, "the off hand's owner was revived as well");
        } finally {
            TestPlayers.leave(user);
            DeathLockout.revive(mainOwner, "Test", "console");
            DeathLockout.revive(offOwner, "Test", "console");
        }
        helper.succeed();
    }

    // A closed store makes every query throw. The lockout check lets everyone in then, so the owner reads as not dead,
    // and the head stays for when the database works again. Without the failing store this would be a real revive.
    @GameTest
    public void reviveWithFailingStoreKeepsHead(GameTestHelper helper) throws IOException {
        UUID owner = lockedOut(helper);
        TestPlayers.Recording user = TestPlayers.joinRecording(helper, "Unlucky", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        Path dir = Files.createTempDirectory("yolt-revive-store");
        DeathStore broken = DeathStore.open(dir.resolve("broken.db"));
        broken.close();
        DeathStore real = DeathLockout.swapStoreForTesting(broken);
        try {
            ItemStack head = reviveHead(staticProfile(owner), 1);
            user.setItemInHand(InteractionHand.MAIN_HAND, head);
            int mark = user.messages.size();
            user.gameMode.useItem(user, user.level(), head, InteractionHand.MAIN_HAND);
            helper.assertValueEqual(user.getMainHandItem().getCount(), 1, "heads left");
            helper.assertValueEqual(said(user, mark), List.of("Ghost is not dead."), "replies");
        } finally {
            DeathLockout.swapStoreForTesting(real);
            TestPlayers.leave(user);
            DeathLockout.revive(owner, "Test", "console");
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
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

    // A fresh owner with a death on record, so a head of theirs has someone to revive
    private static UUID lockedOut(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        DeathLockout.recordDeath(owner, "Ghost");
        helper.assertTrue(DeathLockout.refusal(owner, "Test") != null, "owner is not locked out to begin with");
        return owner;
    }

    // What the user was told since message number "from"
    private static List<String> said(TestPlayers.Recording user, int from) {
        return user.messages.subList(from, user.messages.size()).stream().map(Component::getString).toList();
    }

    private static InteractionResult useOnTop(GameTestHelper helper, ServerPlayer player, ItemStack stack, BlockPos relativePos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos pos = helper.absolutePos(relativePos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), Direction.UP, pos, false);
        return player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.MAIN_HAND, hit);
    }
}
