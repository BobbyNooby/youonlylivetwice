package bobbynooby.dev.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Vanilla only charges a creeper by lightning, which also burns and hurts it; MobBoosts charges a blood-moon creeper
@Mixin(Creeper.class)
public interface CreeperAccessor {
    @Accessor("DATA_IS_POWERED")
    static EntityDataAccessor<Boolean> yoltPowered() {
        throw new AssertionError();
    }
}
