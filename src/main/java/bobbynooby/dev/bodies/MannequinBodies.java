package bobbynooby.dev.bodies;

import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.GraveItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;

/** Builds the vanilla mannequin that stands in for a logged-out player. */
public final class MannequinBodies {
    private MannequinBodies() {
    }

    public static Mannequin spawn(ServerPlayer player, YoltConfig.BodiesSection cfg) {
        ServerLevel level = player.level();
        Mannequin body = new Mannequin(EntityTypes.MANNEQUIN, level);
        // These two have no public setter. They go in through the save keys /summon and old worlds use, which
        // stay stable where private method names may not. Loading resets the whole entity, so it comes first.
        CompoundTag fields = new CompoundTag();
        fields.putBoolean("immovable", !cfg.movable);
        // Hides the "NPC" label; with no custom name there is no nametag either
        fields.putBoolean("hide_description", true);
        body.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), fields));

        body.setComponent(DataComponents.PROFILE, GraveItems.profileOf(player));
        body.setPose(pose(cfg.pose));
        // Keeps the main-hand item in the hand the player held it in
        body.setMainArm(player.getMainArm());
        body.addTag(Bodies.TAG);
        body.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        body.setYHeadRot(player.getYHeadRot());
        body.setYBodyRot(player.yBodyRot);

        for (EquipmentSlot slot : GearSync.SLOTS) {
            ItemStack copy = player.getItemBySlot(slot).copy();
            // A totem only works from a hand
            if (!cfg.totems && slot.getType() == EquipmentSlot.Type.HAND) {
                copy.remove(DataComponents.DEATH_PROTECTION);
            }
            body.setItemSlot(slot, copy);
        }

        if (cfg.copyEffects) {
            // Effects first: health boost and absorption raise the caps the values below are clamped to
            for (MobEffectInstance effect : player.getActiveEffects()) {
                body.addEffect(new MobEffectInstance(effect));
            }
            body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(player.getAttributeBaseValue(Attributes.MAX_HEALTH));
            body.setHealth(player.getHealth());
            body.setAbsorptionAmount(player.getAbsorptionAmount());
        } else {
            body.setHealth(Math.min(player.getHealth(), body.getMaxHealth()));
        }

        level.addFreshEntity(body);
        return body;
    }

    private static Pose pose(YoltConfig.BodyPose pose) {
        return switch (pose) {
            case PRONE -> Pose.SWIMMING;
            case SLEEPING -> Pose.SLEEPING;
            case STANDING -> Pose.STANDING;
        };
    }
}
