package bobbynooby.dev.bodies;

import bobbynooby.dev.config.YoltConfig;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One logged-out player's body: who owns it, which entity stands for it, and its last saved state. A player body also
 * keeps its owner's skin, since it comes back from this record after a restart.
 * <p>
 * emptyAtLogout holds the gear slots the owner had empty when they logged out, so the body's were empty too. Only a
 * dispenser can fill one of those, so an item there is real, not a copy. A record saved before this was kept has
 * none, so nothing on its body counts as real.
 * <p>
 * copiedEffects holds whether the body took its owner's effects when it was made, so the takeover and the expiry hand
 * its effects over only then, whatever bodies.copyEffects says by that time. A record saved before this was kept
 * counts as copied: the switch is on by default, so its body most likely took them, and then its snapshot holds the
 * owner's effects as they ran on the body.
 * <p>
 * held is true for a body made while its owner rode a vehicle that wasn't on dry ground, so it is held where it lies
 * over water, lava or air whatever bodies.movable says. A player body comes back from this record, so it needs it.
 */
public record BodyRecord(
        UUID owner,
        String ownerName,
        UUID bodyEntity,
        YoltConfig.BodyType type,
        long loggedOutAt,
        boolean pendingRemoval,
        BodySnapshot snapshot,
        PropertyMap skin,
        Set<EquipmentSlot> emptyAtLogout,
        boolean copiedEffects,
        boolean held
) {
    public BodyRecord {
        emptyAtLogout = Set.copyOf(emptyAtLogout);
    }

    public static final Codec<BodyRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter(BodyRecord::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(BodyRecord::ownerName),
            UUIDUtil.CODEC.fieldOf("body_entity").forGetter(BodyRecord::bodyEntity),
            Codec.STRING.comapFlatMap(name -> {
                try {
                    return DataResult.success(YoltConfig.BodyType.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "Unknown body type " + name);
                }
            }, type -> type.name().toLowerCase(Locale.ROOT)).fieldOf("type").forGetter(BodyRecord::type),
            Codec.LONG.fieldOf("logged_out_at").forGetter(BodyRecord::loggedOutAt),
            Codec.BOOL.fieldOf("pending_removal").forGetter(BodyRecord::pendingRemoval),
            BodySnapshot.CODEC.fieldOf("snapshot").forGetter(BodyRecord::snapshot),
            // Optional, so records saved before skins were kept still load
            ExtraCodecs.PROPERTY_MAP.optionalFieldOf("skin", PropertyMap.EMPTY).forGetter(BodyRecord::skin),
            EquipmentSlot.CODEC.listOf().<Set<EquipmentSlot>>xmap(Set::copyOf, slots -> slots.stream().sorted().toList())
                    .optionalFieldOf("empty_at_logout", Set.of()).forGetter(BodyRecord::emptyAtLogout),
            // Missing from a record saved before it was kept, which counts as copied
            Codec.BOOL.optionalFieldOf("copied_effects", true).forGetter(BodyRecord::copiedEffects),
            // Missing from a record saved before it was kept, whose body wasn't held
            Codec.BOOL.optionalFieldOf("held", false).forGetter(BodyRecord::held)
    ).apply(i, BodyRecord::new));

    /** A record whose body isn't held, as one saved before that was kept. */
    public BodyRecord(UUID owner, String ownerName, UUID bodyEntity, YoltConfig.BodyType type, long loggedOutAt,
                      boolean pendingRemoval, BodySnapshot snapshot, PropertyMap skin, Set<EquipmentSlot> emptyAtLogout,
                      boolean copiedEffects) {
        this(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, skin, emptyAtLogout, copiedEffects,
                false);
    }

    /** A record that counts nothing on its body as real and its effects as copied, as one saved before either was kept. */
    public BodyRecord(UUID owner, String ownerName, UUID bodyEntity, YoltConfig.BodyType type, long loggedOutAt,
                      boolean pendingRemoval, BodySnapshot snapshot, PropertyMap skin) {
        this(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, skin, Set.of(), true);
    }

    /** A record with no skin kept, as for a mannequin, which keeps its own with its chunk. */
    public BodyRecord(UUID owner, String ownerName, UUID bodyEntity, YoltConfig.BodyType type, long loggedOutAt,
                      boolean pendingRemoval, BodySnapshot snapshot) {
        this(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, PropertyMap.EMPTY);
    }

    public NameAndId who() {
        return new NameAndId(owner, ownerName);
    }

    public BodyRecord withSnapshot(BodySnapshot snapshot) {
        return new BodyRecord(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, skin, emptyAtLogout,
                copiedEffects, held);
    }

    public BodyRecord withPendingRemoval(boolean pendingRemoval) {
        return new BodyRecord(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, skin, emptyAtLogout,
                copiedEffects, held);
    }
}
