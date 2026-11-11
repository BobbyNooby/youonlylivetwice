package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.PlayerCount;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.rcon.thread.QueryThreadGs4;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(QueryThreadGs4.class)
public abstract class PlayerCountQueryMixin {
    // enable-query answers with the real counts and every online name
    @WrapOperation(method = {"processPacket", "buildRuleResponse"}, require = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/ServerInterface;getPlayerCount()I"))
    private int shownOnline(ServerInterface server, Operation<Integer> original) {
        return PlayerCount.online(original.call(server));
    }

    @ModifyExpressionValue(method = {"processPacket", "buildRuleResponse"}, require = 2, at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/rcon/thread/QueryThreadGs4;maxPlayers:I"))
    private int shownMax(int real) {
        return PlayerCount.max(real);
    }

    @WrapOperation(method = "buildRuleResponse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/ServerInterface;getPlayerNames()[Ljava/lang/String;"))
    private String[] shownNames(ServerInterface server, Operation<String[]> original) {
        return PlayerCount.names(original.call(server));
    }
}
