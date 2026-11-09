package bobbynooby.dev.gametest;

import bobbynooby.dev.graves.GraveItems;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;

import java.util.Optional;
import java.util.UUID;

public class GraveItemsGameTests {
    @GameTest
    public void graveHeadCarriesMarkerAndProfile(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Grave", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack head = GraveItems.graveHead(GraveItems.profileOf(player));
            helper.assertTrue(head.is(Items.PLAYER_HEAD), "not a player head");
            helper.assertTrue(GraveItems.isGraveHead(head), "grave marker missing");
            helper.assertFalse(GraveItems.isReviveHead(head), "grave head counted as revive head");
            helper.assertTrue(GraveItems.ownerId(head).filter(player.getUUID()::equals).isPresent(), "owner id wrong");
            helper.succeed();
        } finally {
            TestPlayers.leave(player);
        }
    }

    @GameTest
    public void plainPlayerHeadIsNotMarked(GameTestHelper helper) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        helper.assertFalse(GraveItems.isGraveHead(head), "plain head counted as grave head");
        helper.assertFalse(GraveItems.isReviveHead(head), "plain head counted as revive head");
        helper.assertTrue(GraveItems.ownerId(head).isEmpty(), "plain head has an owner");
        helper.succeed();
    }

    // An offline player's grave head is built from the name and id alone, with no skin to copy
    @GameTest
    public void profileOfNameAndIdKeepsIdAndName(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        ResolvableProfile profile = GraveItems.profileOf(new NameAndId(id, "Ghost"));
        helper.assertValueEqual(profile.partialProfile().id(), id, "profile id");
        helper.assertValueEqual(profile.name(), Optional.of("Ghost"), "profile name");
        helper.assertTrue(profile.partialProfile().properties().values().stream()
                .noneMatch(property -> property.name().equals("textures")), "profile has textures");
        helper.succeed();
    }
}
