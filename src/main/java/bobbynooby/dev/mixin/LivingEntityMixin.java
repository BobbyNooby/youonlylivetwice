package bobbynooby.dev.mixin;


import bobbynooby.dev.features.Graves;
import bobbynooby.dev.graves.GravesManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Shadow
    protected abstract void dropExperience(ServerWorld world, @Nullable Entity attacker);

    @Inject(method = "drop", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;dropInventory(Lnet/minecraft/server/world/ServerWorld;)V", shift = At.Shift.BEFORE), cancellable = true)
    public void createGrave(ServerWorld world, DamageSource damageSource, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayerEntity player) {
            Graves.spawnGrave(player, world, damageSource);
            this.dropExperience(world, damageSource.getAttacker());
            ci.cancel();
        }
    }
}
