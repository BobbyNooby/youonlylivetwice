package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.DebugPrivacy;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class DebugPrivacyBrandMixin {
    // Only the brand the client is sent. getServerModName stays real, since crash reports and the world's save read it.
    @ModifyArg(method = "startConfiguration",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/custom/BrandPayload;<init>(Ljava/lang/String;)V"))
    private String brand(String real) {
        return DebugPrivacy.brand(real);
    }
}
