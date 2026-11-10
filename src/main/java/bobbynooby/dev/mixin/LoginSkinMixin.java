package bobbynooby.dev.mixin;

import bobbynooby.dev.skins.LoginSkins;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginSkinMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    @Final
    private Connection connection;

    @Shadow
    private @Nullable GameProfile authenticatedProfile;

    @Unique
    private @Nullable LoginSkins yoltSkins;

    // Around the whole check, so the skin is in the profile before the login checks, compression and the duplicate-login
    // step. While Mojang is still being asked it returns early, and vanilla calls it again next tick.
    @WrapMethod(method = "verifyLoginAndFinishConnectionSetup")
    private void waitForSkin(GameProfile profile, Operation<Void> original) {
        if (yoltSkins == null) {
            yoltSkins = LoginSkins.start(server, connection.getRemoteAddress(), profile);
        }
        GameProfile ready = yoltSkins.poll();
        if (ready != null) {
            // The player is made from this field once the client acknowledges the login, not from the argument
            authenticatedProfile = ready;
            original.call(ready);
        }
    }
}
