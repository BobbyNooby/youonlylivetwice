package bobbynooby.dev.bodies;

import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Profiles;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.storage.TagValueInput;

/** Builds the vanilla mannequin that stands in for a logged-out player. */
public final class MannequinBody {
    private MannequinBody() {
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

        body.setComponent(DataComponents.PROFILE, Profiles.of(player));
        body.setPose(BodySpawn.pose(cfg.pose));
        body.addTag(Bodies.TAG);
        BodySpawn.placeLike(player, body);
        BodySpawn.copyState(player, body, cfg);

        level.addFreshEntity(body);
        return body;
    }
}
