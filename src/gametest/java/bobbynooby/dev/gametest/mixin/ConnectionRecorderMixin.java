package bobbynooby.dev.gametest.mixin;

import bobbynooby.dev.gametest.PacketRecorder;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hooks a client test's packet recorder into a connection as soon as it is open, before the first byte arrives
@Mixin(Connection.class)
public abstract class ConnectionRecorderMixin {
    @Inject(method = "channelActive", at = @At("TAIL"))
    private void yoltTest$attachRecorder(ChannelHandlerContext ctx, CallbackInfo ci) {
        PacketRecorder.attach((Connection) (Object) this, ctx.channel());
    }
}
