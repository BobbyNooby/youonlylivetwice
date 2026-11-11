package bobbynooby.dev.mixin;

import bobbynooby.dev.core.StandIn;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// A player body's death message would tell everyone its offline owner's name; a mannequin's death sends none
@Mixin(ServerPlayer.class)
public abstract class BodyDeathMessageMixin {
    // The first rule read in die is show_death_messages
    @ModifyExpressionValue(method = "die", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object noDeathMessageForBody(Object showDeathMessages) {
        return StandIn.is((ServerPlayer) (Object) this) ? Boolean.FALSE : showDeathMessages;
    }
}
