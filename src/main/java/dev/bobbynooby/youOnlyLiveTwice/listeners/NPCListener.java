package dev.bobbynooby.youOnlyLiveTwice.listeners;

import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class NPCListener implements Listener {

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {

        NPC npc = event.getNPC();
//        event.getClicker().sendMessage(npc.data().toString());

    }
}
