package bobbynooby.dev.bodies;

import bobbynooby.dev.config.YoltConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/** What both body types do as they are made from their player. */
final class BodySpawn {
    private BodySpawn() {}

    /** Puts the body where its player stands, facing the same way. */
    static void placeLike(ServerPlayer player, Avatar body) {
        // Keeps the main-hand item in the hand the player held it in
        body.setMainArm(player.getMainArm());
        body.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        body.setYHeadRot(player.getYHeadRot());
        body.setYBodyRot(player.yBodyRot);
    }

    /** What both body types take from their player: fall, fire and air, copies of the gear, health and effects. */
    static void copyState(ServerPlayer player, LivingEntity body, YoltConfig.BodiesSection cfg) {
        for (EquipmentSlot slot : GearSync.SLOTS) {
            ItemStack copy = player.getItemBySlot(slot).copy();
            // A totem only works from a hand
            if (!cfg.totems && slot.getType() == EquipmentSlot.Type.HAND) {
                copy.remove(DataComponents.DEATH_PROTECTION);
            }
            body.setItemSlot(slot, copy);
        }
        applyGearModifiers(body);
        if (cfg.copyEffects) {
            body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(player.getAttributeBaseValue(Attributes.MAX_HEALTH));
        }
        // Logging out mid-fall, on fire or under water doesn't get the player out of it. Without the effects, the
        // health is clamped to the body's own maximum and there is no absorption cap to keep any.
        BodySnapshot.of(player).applyTo(body, cfg.copyEffects);
    }

    /**
     * Puts the attribute modifiers of the body's gear on it now rather than at its first tick, since setting the
     * health clamps it to the maximum: a chestplate's extra health would be cut off.
     */
    static void applyGearModifiers(LivingEntity body) {
        for (EquipmentSlot slot : GearSync.SLOTS) {
            ItemStack stack = body.getItemBySlot(slot);
            if (stack.isEmpty() || stack.isBroken()) {
                continue;
            }
            // As vanilla's equipment check adds them, so that check at the first tick finds them already on
            stack.forEachModifier(slot, (attribute, modifier) -> {
                AttributeInstance instance = body.getAttribute(attribute);
                if (instance != null) {
                    instance.removeModifier(modifier.id());
                    instance.addTransientModifier(modifier);
                }
            });
        }
    }

    static Pose pose(YoltConfig.BodyPose pose) {
        return switch (pose) {
            case PRONE -> Pose.SWIMMING;
            case SLEEPING -> Pose.SLEEPING;
            case STANDING -> Pose.STANDING;
        };
    }
}
