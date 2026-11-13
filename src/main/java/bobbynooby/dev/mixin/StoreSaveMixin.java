package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.core.AtomicFiles;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.skins.SkinStore;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.nio.file.Path;

@Mixin(SavedDataStorage.class)
public abstract class StoreSaveMixin {
    // Vanilla writes over the file in place, so a crash mid-save would leave YOLT's stores cut off and read as empty
    @WrapOperation(method = "tryWrite", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void writeYoltStoresAtomically(CompoundTag tag, Path path, Operation<Void> original,
                                           @Local(argsOnly = true) SavedDataType<?> type) throws IOException {
        if (type.equals(GraveStore.TYPE) || type.equals(BodyStore.TYPE) || type.equals(SkinStore.TYPE)) {
            AtomicFiles.write(path, tmp -> original.call(tag, tmp));
        } else {
            original.call(tag, path);
        }
    }
}
