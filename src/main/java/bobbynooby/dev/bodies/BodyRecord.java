package bobbynooby.dev.bodies;

import bobbynooby.dev.config.YoltConfig;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;

import java.util.Locale;
import java.util.UUID;

/**
 * One logged-out player's body: who owns it, which entity stands for it, and its last saved state. A player body also
 * keeps its owner's skin, since it comes back from this record after a restart.
 */
public record BodyRecord(
        UUID owner,
        String ownerName,
        UUID bodyEntity,
        YoltConfig.BodyType type,
        long loggedOutAt,
        boolean pendingRemoval,
        BodySnapshot snapshot,
        PropertyMap skin
) {
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
            ExtraCodecs.PROPERTY_MAP.optionalFieldOf("skin", PropertyMap.EMPTY).forGetter(BodyRecord::skin)
    ).apply(i, BodyRecord::new));

    /** A record with no skin kept, as for a mannequin, which keeps its own with its chunk. */
    public BodyRecord(UUID owner, String ownerName, UUID bodyEntity, YoltConfig.BodyType type, long loggedOutAt,
                      boolean pendingRemoval, BodySnapshot snapshot) {
        this(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, PropertyMap.EMPTY);
    }

    public BodyRecord withSnapshot(BodySnapshot snapshot) {
        return new BodyRecord(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, skin);
    }

    public BodyRecord withPendingRemoval(boolean pendingRemoval) {
        return new BodyRecord(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot, skin);
    }
}
