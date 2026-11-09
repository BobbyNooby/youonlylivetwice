package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.PlayerCount;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.ServerInfo;
import net.minecraft.server.network.LegacyQueryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LegacyQueryHandler.class)
public abstract class PlayerCountLegacyMixin {
    // The pre-1.7 ping reads the counts straight from the server, not from the status record
    @WrapOperation(method = {"createVersion0Response", "createVersion1Response"}, require = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/ServerInfo;getPlayerCount()I"))
    private static int shownOnline(ServerInfo server, Operation<Integer> original) {
        return PlayerCount.online(original.call(server));
    }

    @WrapOperation(method = {"createVersion0Response", "createVersion1Response"}, require = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/ServerInfo;getMaxPlayers()I"))
    private static int shownMax(ServerInfo server, Operation<Integer> original) {
        return PlayerCount.max(original.call(server));
    }
}
