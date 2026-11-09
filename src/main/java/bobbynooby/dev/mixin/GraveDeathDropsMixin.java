package bobbynooby.dev.mixin;

import bobbynooby.dev.graves.GraveDeath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ServerPlayer doesn't override dropEquipment. XP drops in a separate call afterwards, so it still drops.
@Mixin(Player.class)
public abstract class GraveDeathDropsMixin {
    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void moveDropsIntoGrave(ServerLevel level, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && GraveDeath.onPlayerDeathDrops(player, level)) {
            ci.cancel();
        }
    }
}
