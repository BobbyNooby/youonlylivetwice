package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.Bodies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Loot table, equipment and XP all drop from here, so a datapack loot table or XP enchantment can't make a body
// drop anything. Vanilla's mannequin drops nothing already; the owner's grave gets the real items.
@Mixin(LivingEntity.class)
public abstract class BodyDeathLootMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void dropNothingForBody(ServerLevel level, DamageSource source, CallbackInfo ci) {
        if (Bodies.dropsNothing((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
