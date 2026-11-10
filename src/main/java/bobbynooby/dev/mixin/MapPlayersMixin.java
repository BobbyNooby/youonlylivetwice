package bobbynooby.dev.mixin;

import bobbynooby.dev.visibility.MapPlayers;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.Map;

@Mixin(MapItemSavedData.HoldingPlayer.class)
public abstract class MapPlayersMixin {
    // Vanilla sends each holder every marker on the map, so a copy shows where every other holder is, at any distance.
    // Both the carried and the framed copy's update come through here.
    @WrapOperation(method = "nextUpdatePacket", at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;"))
    private Collection<MapDecoration> onlyPlayersInSight(Map<String, MapDecoration> markers,
                                                         Operation<Collection<MapDecoration>> original) {
        return MapPlayers.visibleTo((MapItemSavedData.HoldingPlayer) (Object) this, markers, original.call(markers));
    }

    // Vanilla sends the markers again only when one changes, so a player who left the holder's sight would stay on it
    @ModifyExpressionValue(method = "nextUpdatePacket", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData$HoldingPlayer;dirtyDecorations:Z"))
    private boolean resendAfterLosingSight(boolean dirty) {
        return dirty || MapPlayers.resendDue((MapItemSavedData.HoldingPlayer) (Object) this);
    }
}
