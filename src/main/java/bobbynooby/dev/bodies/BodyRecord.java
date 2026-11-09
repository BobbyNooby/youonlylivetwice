package bobbynooby.dev.bodies;

import bobbynooby.dev.config.YoltConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/** One logged-out player's body: who owns it, which entity stands for it, and its last saved state. */
public record BodyRecord(
        UUID owner,
        String ownerName,
        UUID bodyEntity,
        YoltConfig.BodyType type,
        long loggedOutAt,
        boolean pendingRemoval,
        BodySnapshot snapshot
) {
    public static final Codec<BodyRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter(BodyRecord::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(BodyRecord::ownerName),
            UUIDUtil.CODEC.fieldOf("body_entity").forGetter(BodyRecord::bodyEntity),
            Codec.STRING.xmap(name -> YoltConfig.BodyType.valueOf(name.toUpperCase(java.util.Locale.ROOT)),
                    type -> type.name().toLowerCase(java.util.Locale.ROOT)).fieldOf("type").forGetter(BodyRecord::type),
            Codec.LONG.fieldOf("logged_out_at").forGetter(BodyRecord::loggedOutAt),
            Codec.BOOL.fieldOf("pending_removal").forGetter(BodyRecord::pendingRemoval),
            BodySnapshot.CODEC.fieldOf("snapshot").forGetter(BodyRecord::snapshot)
    ).apply(i, BodyRecord::new));

    public BodyRecord withSnapshot(BodySnapshot snapshot) {
        return new BodyRecord(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot);
    }

    public BodyRecord withPendingRemoval(boolean pendingRemoval) {
        return new BodyRecord(owner, ownerName, bodyEntity, type, loggedOutAt, pendingRemoval, snapshot);
    }
}
