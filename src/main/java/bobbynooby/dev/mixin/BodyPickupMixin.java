package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.PlayerBody;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Items, XP and arrows a player walks over all go through here. Only a body's worn gear goes back to its owner, so
// anything a player body picked up would be lost with it. It also stops the contact damage slimes, magma cubes and
// pufferfish deal players. A mannequin doesn't take that either: by contact, slimes only hurt players and iron golems,
// and pufferfish only players and mobs.
@Mixin(Player.class)
public abstract class BodyPickupMixin {
    @Inject(method = "touch", at = @At("HEAD"), cancellable = true)
    private void noPickupForBody(Entity entity, CallbackInfo ci) {
        if ((Object) this instanceof PlayerBody) {
            ci.cancel();
        }
    }
}
