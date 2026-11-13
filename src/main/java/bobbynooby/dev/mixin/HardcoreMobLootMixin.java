package bobbynooby.dev.mixin;

import bobbynooby.dev.hardcore.BloodMoon;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(LivingEntity.class)
public abstract class HardcoreMobLootMixin {
    // Only the loot-table roll is repeated, never the equipment drop beside it. The table is read first, since a mob
    // given one of its own forgets it once it has rolled it.
    @WrapOperation(method = "dropAllDeathLoot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V"))
    private void rollAgain(LivingEntity self, ServerLevel level, DamageSource source, boolean playerKilled,
                           Operation<Void> original) {
        Optional<ResourceKey<LootTable>> lootTable = self.getLootTable();
        original.call(self, level, source, playerKilled);
        BloodMoon.extraLoot(self, level, source, playerKilled, lootTable);
    }

    @ModifyReturnValue(method = "getExperienceReward", at = @At("RETURN"))
    private int bloodMoonExperience(int experience) {
        return BloodMoon.experience((LivingEntity) (Object) this, experience);
    }
}
