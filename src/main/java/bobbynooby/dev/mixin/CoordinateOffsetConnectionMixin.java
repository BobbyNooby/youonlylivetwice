package bobbynooby.dev.mixin;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.OffsetHolder;
import bobbynooby.dev.coords.OffsetState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Connection.class)
public abstract class CoordinateOffsetConnectionMixin implements OffsetHolder {
    @Unique
    private final OffsetState yoltOffsetState = new OffsetState();

    @Override
    public OffsetState yoltOffsetState() {
        return yoltOffsetState;
    }

    // Every send ends here, on the sending thread and before the packet is queued, so frames switch at the exact packet.
    // Around the whole method, since a packet that can't be translated must not go out at all.
    @WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V")
    private void translateSent(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, Operation<Void> original) {
        Packet<?> translated = CoordinateOffsets.outgoing((Connection) (Object) this, packet);
        if (translated != null) {
            original.call(translated, listener, flush);
        }
    }

    // Every packet the client sends is handed to its listener here, on the network thread
    @WrapOperation(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw"
                    + "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void translateReceived(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        Packet<?> translated = CoordinateOffsets.incoming((Connection) (Object) this, packet);
        if (translated != null) {
            original.call(translated, listener);
        }
    }
}
