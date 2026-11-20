package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.ChatDelivery;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// A pet's death message goes straight to its owner, wherever they are, past the hook that hides game messages. It names
// the killer, so it would tell the owner who is near their pet.
@Mixin(TamableAnimal.class)
public abstract class PetDeathMessageMixin {
    @WrapWithCondition(method = "die", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V"))
    private boolean tellOwnerOnlyIfTheySeeGameMessages(ServerPlayer owner, Component message) {
        return ChatDelivery.seesGameMessages(owner);
    }
}
