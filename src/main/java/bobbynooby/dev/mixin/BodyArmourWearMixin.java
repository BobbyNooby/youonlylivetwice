package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.Bodies;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Both are empty here and only Player fills them in, so a body's armour never wore down. These do what Player's do.
@Mixin(LivingEntity.class)
public abstract class BodyArmourWearMixin {
    @Shadow
    protected abstract void doHurtEquipment(DamageSource source, float damage, EquipmentSlot... slots);

    @Inject(method = "hurtArmor", at = @At("HEAD"))
    private void wearBodyArmour(DamageSource source, float damage, CallbackInfo ci) {
        if (Bodies.wearsArmour((LivingEntity) (Object) this)) {
            doHurtEquipment(source, damage, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        }
    }

    // A falling block or stalactite on the head
    @Inject(method = "hurtHelmet", at = @At("HEAD"))
    private void wearBodyHelmet(DamageSource source, float damage, CallbackInfo ci) {
        if (Bodies.wearsArmour((LivingEntity) (Object) this)) {
            doHurtEquipment(source, damage, EquipmentSlot.HEAD);
        }
    }
}
