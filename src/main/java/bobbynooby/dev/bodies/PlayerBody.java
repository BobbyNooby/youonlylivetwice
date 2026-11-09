package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.features.NameTagHider;
import com.mojang.authlib.GameProfile;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.TextFilter;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A body that is a real player entity: it is in the world, so its area keeps running and mobs spawn around it, but it
 * never joins the player list, so it never counts as online.
 */
public final class PlayerBody extends ServerPlayer implements StandIn {
    private static final byte ALL_SKIN_LAYERS = allSkinLayers();

    private final UUID owner;
    private final Pose bodyPose;
    private final boolean movable;

    private PlayerBody(ServerLevel level, GameProfile profile, UUID owner, YoltConfig.BodiesSection cfg) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        this.owner = owner;
        this.bodyPose = MannequinBodies.pose(cfg.pose);
        this.movable = cfg.movable;
        // The owner gets the body's health back on login, so healing here would heal them for logging out
        this.foodData = new FoodData() {
            @Override
            public void tick(ServerPlayer player) {
            }
        };
        // Sets this.connection
        new BodyPacketListener(this);
        // A creative or adventure default would make the body something mobs and players can't hurt
        setGameMode(GameType.SURVIVAL);
        // The client information has every layer off, which would draw the skin without its outer layer
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, ALL_SKIN_LAYERS);
        // Set now too, so the hitbox is right before the first tick
        setPose(bodyPose);
        // Vanilla also tracks a transmitter again whenever this range changes, as when invisibility wears off
        getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE).setBaseValue(0.0);
        addTag(Bodies.TAG);
    }

    public static PlayerBody spawn(ServerPlayer player, YoltConfig.BodiesSection cfg) {
        ServerLevel level = player.level();
        GameProfile from = player.getGameProfile();
        // A player entity's id is its profile's, and the world refuses a second entity with the owner's id
        PlayerBody body = new PlayerBody(level, new GameProfile(UUID.randomUUID(), from.name(), from.properties()),
                player.getUUID(), cfg);
        // Keeps the main-hand item in the hand the player held it in
        body.setMainArm(player.getMainArm());
        body.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        body.setYHeadRot(player.getYHeadRot());
        body.setYBodyRot(player.yBodyRot);
        MannequinBodies.copyState(player, body, cfg);
        body.addToWorld(level);
        return body;
    }

    /** Brings back a body from its record after a restart. Null when its dimension is gone or it can't be added. */
    public static @Nullable PlayerBody fromRecord(MinecraftServer server, BodyRecord record) {
        BodySnapshot snapshot = record.snapshot();
        ServerLevel level = server.getLevel(snapshot.dimension());
        if (level == null) {
            return null;
        }
        // Same body id as before, so the record still points at it
        PlayerBody body = new PlayerBody(level, new GameProfile(record.bodyEntity(), record.ownerName(), record.skin()),
                record.owner(), Settings.get().bodies);
        Vec3 pos = snapshot.pos();
        body.snapTo(pos.x, pos.y, pos.z, snapshot.yRot(), 0.0F);
        body.setYHeadRot(snapshot.yRot());
        body.setYBodyRot(snapshot.yRot());
        snapshot.gear().forEach((slot, stack) -> body.setItemSlot(slot, stack.copy()));
        // Its effects are its own, from when it was made
        snapshot.applyTo(body, true);
        return body.addToWorld(level) ? body : null;
    }

    // Like a player: the name goes in the nametag team before anyone can see the body
    private boolean addToWorld(ServerLevel level) {
        NameTagHider.addToTeam(level.getServer(), this);
        return level.addFreshEntity(this);
    }

    public UUID owner() {
        return owner;
    }

    /** The owner's own profile with the body's skin, for the grave head. The body's profile has the body's id. */
    public ResolvableProfile ownerProfile() {
        return ResolvableProfile.createResolved(new GameProfile(owner, getGameProfile().name(), getGameProfile().properties()));
    }

    // A client would send the movement that runs the rest of a player's tick: physics, effects, fire and air
    @Override
    public void tick() {
        super.tick();
        if (isRemoved()) {
            return;
        }
        doTick();
        // A client's movement also moves a player in the chunk map, which keeps its area loaded and lets mobs spawn
        // there. Only when it changes section: the move checks every tracked entity.
        if (!isRemoved() && !SectionPos.of(this).equals(getLastSectionPos())) {
            level().getChunkSource().move(this);
        }
    }

    // So the server moves the body itself, and checks its fall damage as it lands
    @Override
    public boolean isClientAuthoritative() {
        return false;
    }

    // Vanilla picks the pose from what the client is doing, which would stand the body up every tick
    @Override
    protected void updatePlayerPose() {
        if (!isDeadOrDying()) {
            setPose(bodyPose);
        }
    }

    // The same as an immovable mannequin
    @Override
    protected boolean isImmobile() {
        return !movable || super.isImmobile();
    }

    @Override
    public boolean isEffectiveAi() {
        return movable && super.isEffectiveAi();
    }

    // Vanilla lets nothing but an ender pearl hurt a player changing dimension, until its client accepts the teleport.
    // A body has no client, so after a /tp to another dimension it could never be hurt or killed again.
    @Override
    public @Nullable ServerPlayer teleport(TeleportTransition transition) {
        ServerPlayer moved = super.teleport(transition);
        hasChangedDimension();
        return moved;
    }

    // The locator bar would point every player at an offline player's body; a mannequin sends nothing either
    @Override
    public boolean isTransmittingWaypoint() {
        return false;
    }

    // No client to show a locator bar to
    @Override
    public boolean isReceivingWaypoints() {
        return false;
    }

    // Peaceful healing, for the same reason as the food above
    @Override
    protected void tickRegeneration() {
    }

    // Read live, so turning the switch off also stops a hunt already under way
    @Override
    public boolean canBeSeenAsEnemy() {
        return Settings.on(c -> c.bodies.mobsAttack) && super.canBeSeenAsEnemy();
    }

    // Player's own wear, which skips the mixin a mannequin goes through, so the switch is checked here
    @Override
    public void hurtArmor(DamageSource source, float damage) {
        if (Bodies.wearsArmour(this)) {
            super.hurtArmor(source, damage);
        }
    }

    @Override
    public void hurtHelmet(DamageSource source, float damage) {
        if (Bodies.wearsArmour(this)) {
            super.hurtHelmet(source, damage);
        }
    }

    // A stat objective on the scoreboard is kept under the body's name, which is its owner's
    @Override
    public void awardStat(Stat<?> stat, int count) {
    }

    @Override
    public void resetStat(Stat<?> stat) {
    }

    /** The body's own stats, never stored in the player list, so nothing is kept or written for the body's id. */
    public static ServerStatsCounter detachedStats(PlayerBody body) {
        MinecraftServer server = body.level().getServer();
        return new ServerStatsCounter(server, server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(body.getUUID() + ".json")) {
            @Override
            public void save() {
            }
        };
    }

    /** The body's own advancements, kept the same way. It never makes one: the chat would announce its owner's name. */
    public static PlayerAdvancements detachedAdvancements(PlayerBody body) {
        MinecraftServer server = body.level().getServer();
        PlayerAdvancements advancements = new PlayerAdvancements(server.getFixerUpper(), server.getPlayerList(), server.getAdvancements(),
                server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(body.getUUID() + ".json"), body) {
            @Override
            public boolean award(AdvancementHolder holder, String criterion) {
                return false;
            }

            @Override
            public void save() {
            }
        };
        // Nothing to check its criteria against on every tick and move
        advancements.clearTriggers();
        return advancements;
    }

    // A text filter service would be told a player joined who never leaves
    @Override
    public TextFilter getTextFilter() {
        return TextFilter.DUMMY;
    }

    private static byte allSkinLayers() {
        int mask = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            mask |= part.getMask();
        }
        return (byte) mask;
    }
}
