package dev.bobbynooby.youOnlyLiveTwice.npc;

import dev.bobbynooby.youOnlyLiveTwice.YouOnlyLiveTwice;
import dev.bobbynooby.youOnlyLiveTwice.utils.LocalDatabase;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.Inventory;
import org.bukkit.event.EventHandler;

public class CorpseNPCTrait extends Trait {

    private final LocalDatabase db;
    private final YouOnlyLiveTwice plugin;

    public CorpseNPCTrait(LocalDatabase db, YouOnlyLiveTwice plugin) {
        super("CorpseNPCTrait");
        this.db = db;
        this.plugin = plugin;
    }


    @EventHandler
    public void onDeath(NPCDeathEvent event) {
        NPC npc = event.getNPC();
    }
}
