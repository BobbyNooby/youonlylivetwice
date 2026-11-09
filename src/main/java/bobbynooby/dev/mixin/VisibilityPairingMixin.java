package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
public abstract class VisibilityPairingMixin {
    @Shadow
    @Final
    private Entity entity;

    // The client refuses to spawn a player it has no entry for, so the entry goes out first
    @Inject(method = "addPairing", at = @At("HEAD"))
    private void sendEntryBeforeSpawn(ServerPlayer viewer, CallbackInfo ci) {
        if (Settings.active() && this.entity instanceof ServerPlayer seen) {
            PlayerVisibility.onPairingStart(viewer, seen);
        }
    }

    // After the entity is gone, so the client never holds a player without an entry
    @Inject(method = "removePairing", at = @At("TAIL"))
    private void dropEntryAfterDespawn(ServerPlayer viewer, CallbackInfo ci) {
        if (Settings.active() && this.entity instanceof ServerPlayer seen) {
            PlayerVisibility.onPairingStop(viewer, seen);
        }
    }
}
