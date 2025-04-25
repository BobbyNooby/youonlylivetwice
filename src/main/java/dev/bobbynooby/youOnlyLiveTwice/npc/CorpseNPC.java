package dev.bobbynooby.youOnlyLiveTwice.npc;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.trait.EntityPoseTrait;
import org.bukkit.entity.Player;

public class CorpseNPC extends AliveNPC {

    public CorpseNPC(Player player, LocalDatabase db, YouOnlyLiveTwice plugin) {
        super(player, db, plugin);
        npc.removeTrait(AliveNPCTrait.class);

        npc.addTrait(new CorpseNPCTrait());
        npc.addTrait(Inventory.class);

        npc.getTraitNullable(EntityPoseTrait.class).setPose(EntityPoseTrait.EntityPose.SWIMMING);


    }
}
