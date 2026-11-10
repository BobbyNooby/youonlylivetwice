package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.VanillaKill;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Collection;

@Mixin(KillCommand.class)
public abstract class VanillaKillMixin {
    // Around the method both forms of /kill end in, so the victims are known before any dies and vanilla's own message
    // counts only the ones that did
    @WrapMethod(method = "kill")
    private static int skipBodies(CommandSourceStack source, Collection<? extends Entity> victims, Operation<Integer> original) {
        return VanillaKill.kill(source, victims, others -> original.call(source, others));
    }
}
