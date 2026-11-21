package bobbynooby.dev.elytra.mixin;

import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Whether a rocket is boosting the entity it is stuck to, which the elytra nerf ends past its cut-off. Only the private
// check knows; a crossbow's rocket has the same owner but flies free. The method's name carries the mod's, since every
// mod's invokers on a class share one namespace.
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketInvoker {
    @Invoker("isAttachedToEntity")
    boolean yoltElytraIsAttachedToEntity();
}
