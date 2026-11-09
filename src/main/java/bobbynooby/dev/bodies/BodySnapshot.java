package bobbynooby.dev.bodies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** What a body looked like when last saved: where it stands, its health, fall, fire and air, effects and worn gear. */
public record BodySnapshot(
        ResourceKey<Level> dimension,
        Vec3 pos,
        float yRot,
        float health,
        float absorption,
        double fallDistance,
        int fireTicks,
        int air,
        List<MobEffectInstance> effects,
        Map<EquipmentSlot, ItemStack> gear
) {
    // Compact constructor: an empty stack can't be encoded and would crash the world save
    public BodySnapshot {
        effects = List.copyOf(effects);
        EnumMap<EquipmentSlot, ItemStack> kept = new EnumMap<>(EquipmentSlot.class);
        gear.forEach((slot, stack) -> {
            if (!stack.isEmpty()) {
                kept.put(slot, stack);
            }
        });
        gear = kept;
    }

    public static final Codec<BodySnapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(BodySnapshot::dimension),
            Vec3.CODEC.fieldOf("pos").forGetter(BodySnapshot::pos),
            Codec.FLOAT.fieldOf("y_rot").forGetter(BodySnapshot::yRot),
            Codec.FLOAT.fieldOf("health").forGetter(BodySnapshot::health),
            Codec.FLOAT.fieldOf("absorption").forGetter(BodySnapshot::absorption),
            // Optional, so records saved before these were kept still load. Mannequins and players all have
            // vanilla's full air supply.
            Codec.DOUBLE.optionalFieldOf("fall_distance", 0.0).forGetter(BodySnapshot::fallDistance),
            Codec.INT.optionalFieldOf("fire_ticks", 0).forGetter(BodySnapshot::fireTicks),
            Codec.INT.optionalFieldOf("air", Entity.TOTAL_AIR_SUPPLY).forGetter(BodySnapshot::air),
            MobEffectInstance.CODEC.listOf().fieldOf("effects").forGetter(BodySnapshot::effects),
            Codec.unboundedMap(EquipmentSlot.CODEC, ItemStack.CODEC).fieldOf("gear").forGetter(BodySnapshot::gear)
    ).apply(i, BodySnapshot::new));

    /**
     * Puts this fall, fire, air, health and absorption, and the effects when asked, on a body or a player. Effects go
     * first: health boost and absorption raise the caps the health and absorption are clamped to. The effects are
     * copies, so a record kept for later doesn't change as they tick.
     */
    public void applyTo(LivingEntity entity, boolean copyEffects) {
        entity.fallDistance = fallDistance;
        entity.setRemainingFireTicks(fireTicks);
        entity.setAirSupply(air);
        if (copyEffects) {
            entity.removeAllEffects();
            for (MobEffectInstance effect : effects) {
                entity.addEffect(new MobEffectInstance(effect));
            }
        }
        entity.setHealth(health);
        entity.setAbsorptionAmount(absorption);
    }

    /** Reads a live body, mannequin or player type. Effects and stacks are copied, so later changes don't leak in. */
    public static BodySnapshot of(LivingEntity body) {
        List<MobEffectInstance> effects = new ArrayList<>();
        for (MobEffectInstance effect : body.getActiveEffects()) {
            effects.add(new MobEffectInstance(effect));
        }
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : GearSync.SLOTS) {
            gear.put(slot, body.getItemBySlot(slot).copy());
        }
        return new BodySnapshot(body.level().dimension(), body.position(), body.getYRot(), body.getHealth(),
                body.getAbsorptionAmount(), body.fallDistance, body.getRemainingFireTicks(), body.getAirSupply(), effects, gear);
    }
}
