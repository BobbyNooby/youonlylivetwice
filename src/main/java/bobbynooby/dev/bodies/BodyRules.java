package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;

/** What counts as a body, and how a body differs from other entities: no drops, worn armour, no portals. */
public final class BodyRules {
    private BodyRules() {}

    /** True for anything we spawned as a body: it wears copies of its owner's saved gear, so its death drops nothing. */
    public static boolean dropsNothing(LivingEntity entity) {
        return Settings.active() && isTaggedBody(entity);
    }

    /** True when hits should wear down a body's armour, as they do a player's. */
    public static boolean wearsArmour(LivingEntity entity) {
        return Settings.on(c -> c.bodies.armourWears) && isBody(entity);
    }

    /**
     * Bodies are kept out of portals on purpose: travel through them is left for later. One an admin moves to another
     * dimension is still found and handed back. A vehicle carrying one stays out too, since vanilla takes the
     * passengers along without asking them.
     */
    public static boolean blocksPortal(Entity entity) {
        if (!Settings.active()) {
            return false;
        }
        if (isTaggedBody(entity)) {
            return true;
        }
        // Every passenger, not only the first: a boat holds two, and a passenger can carry passengers of its own
        for (Entity passenger : entity.getIndirectPassengers()) {
            if (isTaggedBody(passenger)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The damage a hit deals a mannequin body, scaled for the difficulty as vanilla scales it for a player
     * (Player.hurtServer), so the body takes what its owner would. Vanilla scales it for players only, so a mob hits a
     * mannequin as on Normal. A player body is a player already, and anything else takes the damage as dealt.
     */
    public static float damageTaken(LivingEntity entity, ServerLevel level, DamageSource source, float damage) {
        if (!(entity instanceof Mannequin) || !source.scalesWithDifficulty() || !Settings.active() || !isBody(entity)) {
            return damage;
        }
        return switch (level.getDifficulty()) {
            case PEACEFUL -> 0.0F;
            case EASY -> Math.min(damage / 2.0F + 1.0F, damage);
            case NORMAL -> damage;
            case HARD -> damage * 3.0F / 2.0F;
        };
    }

    /** True unless damageTaken cut the hit to nothing, as on Peaceful: then, as for a player, it doesn't land. */
    public static boolean hitLands(float damage, float taken) {
        return !(taken == 0.0F && damage > 0.0F);
    }

    /** True when the entity carries our tag and a record still points at it. */
    public static boolean isBody(Entity entity) {
        return entity.entityTags().contains(Bodies.TAG) && entity.level() instanceof ServerLevel level
                && BodyStore.get(level.getServer()).byEntity(entity.getUUID()).isPresent();
    }

    // An admin can put the tag on a real player too, whose items are real. A player-type body is never in the
    // player list, so it still counts.
    private static boolean isTaggedBody(Entity entity) {
        return entity.entityTags().contains(Bodies.TAG) && !(entity instanceof ServerPlayer player
                && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player);
    }
}
