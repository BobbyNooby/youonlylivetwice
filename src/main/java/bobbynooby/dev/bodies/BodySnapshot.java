package bobbynooby.dev.bodies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** What a body looked like when last saved: where it stands, its health, effects and worn gear. */
public record BodySnapshot(
        ResourceKey<Level> dimension,
        Vec3 pos,
        float yRot,
        float health,
        float absorption,
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
            MobEffectInstance.CODEC.listOf().fieldOf("effects").forGetter(BodySnapshot::effects),
            Codec.unboundedMap(EquipmentSlot.CODEC, ItemStack.CODEC).fieldOf("gear").forGetter(BodySnapshot::gear)
    ).apply(i, BodySnapshot::new));

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
                body.getAbsorptionAmount(), effects, gear);
    }
}
