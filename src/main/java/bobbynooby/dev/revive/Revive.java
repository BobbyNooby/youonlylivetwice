package bobbynooby.dev.revive;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;
import java.util.UUID;

/** Using a revive head ends its owner's death lockout. */
public final class Revive {
    public enum Outcome {
        REVIVED,
        NOT_DEAD,
        NO_OWNER,
        DISABLED,
        // The store refused the change, so the head is kept
        FAILED
    }

    private Revive() {
    }

    // Right-click in the air, and on a block. The block hook only runs when the grave one passes, so a click on a
    // grave opens it instead.
    public static void init() {
        UseItemCallback.EVENT.register(Revive::onUseItem);
        UseBlockCallback.EVENT.register(Revive::onUseBlock);
    }

    /** Tells the user what happened. Only a revive that went through uses up one head. */
    public static Outcome tryUse(ServerPlayer user, ItemStack stack) {
        if (!Settings.on(c -> c.revive.enabled)) {
            user.sendSystemMessage(Component.literal("Revives are turned off."));
            return Outcome.DISABLED;
        }
        Optional<UUID> owner = GraveItems.ownerId(stack);
        if (owner.isEmpty()) {
            user.sendSystemMessage(Component.literal("This head has no owner."));
            return Outcome.NO_OWNER;
        }
        String name = ownerName(stack, owner.get());
        if (DeathLockout.refusal(owner.get()) == null) {
            user.sendSystemMessage(Component.literal(name + " is not dead"));
            return Outcome.NOT_DEAD;
        }
        DeathLockout.ReviveResult result = DeathLockout.revive(owner.get(), user.nameAndId().name());
        if (result == DeathLockout.ReviveResult.FAILED) {
            user.sendSystemMessage(Component.literal("Could not revive " + name + ". Try again later."));
            return Outcome.FAILED;
        }
        if (result != DeathLockout.ReviveResult.REVIVED) {
            // The lockout ran out between the check above and now
            user.sendSystemMessage(Component.literal(name + " is not dead"));
            return Outcome.NOT_DEAD;
        }
        stack.shrink(1);
        // Sent straight to the user: Player.playSound skips the player it is played for
        user.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.TOTEM_USE),
                SoundSource.PLAYERS, user.getX(), user.getY(), user.getZ(), 1.0F, 1.0F, user.getRandom().nextLong()));
        user.sendSystemMessage(Component.literal(name + " can come back now."));
        Yolt.LOGGER.info("{} revived {} with a revive head", user.nameAndId().name(), name);
        return Outcome.REVIVED;
    }

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        // The event also fires on the client; only the server decides
        if (!Settings.active() || !(player instanceof ServerPlayer user) || user.isSpectator()) {
            return InteractionResult.PASS;
        }
        ItemStack stack = user.getItemInHand(hand);
        if (!GraveItems.isReviveHead(stack)) {
            return InteractionResult.PASS;
        }
        // The client saw no use for a head, so it didn't swing; the server swings for it
        return tryUse(user, stack) == Outcome.REVIVED ? InteractionResult.SUCCESS_SERVER : InteractionResult.FAIL;
    }

    // Right-clicking with a revive head always means trying to revive, so the clicked block never reacts. A grave
    // head passes: the block reacts as usual, and GraveBlocks.refusePlacement stops it being placed.
    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!Settings.active() || !(player instanceof ServerPlayer user) || user.isSpectator()) {
            return InteractionResult.PASS;
        }
        ItemStack stack = user.getItemInHand(hand);
        if (!GraveItems.isReviveHead(stack)) {
            return InteractionResult.PASS;
        }
        tryUse(user, stack);
        // The client placed the head on its side and took it off the stack, so send it the real stack
        user.inventoryMenu.sendAllDataToRemote();
        return InteractionResult.FAIL;
    }

    // A head given by id alone has no name to show
    private static String ownerName(ItemStack stack, UUID owner) {
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        String name = profile == null ? "" : profile.name().orElse("");
        return name.isEmpty() ? owner.toString() : name;
    }
}
