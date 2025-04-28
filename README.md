# You Only Live Twice

A minecraft server-side fabric mod intended to increase the stakes of a multiplayer minecraft playthrough by removing
several features usually taken for granted and provide an overall more immersive experience.

# Usage

This mod is intended to be used on its own with a few exceptions listed below. The addition of other mods might cause
issues.

Some mods I highly recommend to use along with this are:

- [SimpleVoiceChat](https://modrinth.com/plugin/simple-voice-chat)
    - This is the main mod i originally intended to use this mod with for an SMP.
- [Chunky](https://modrinth.com/plugin/chunky)
    - Since the world border is so small, Chunky can be used to basically prerender the whole world.

Upon starting the server, a config file will be created in the run/config folder. And the world borders will be set to a
diameter of 8000 by default. The config can be changed in the file before starting the server or by using the /yolt
command in-game.

Since this is a server sided mod, all config is done through the /yolt command.

# Planned Features

- ### Pseudo Hardcore
    - Dead players are barred from playing
    - Ghost (spectator) system ? Purgatory world ? Just banned from the server ?
    - ### Grave System
        - Players leave a grave with their items and their player head in it.
    - ### Revive system
        - Players heads can be used with a custom crafting recipe item or a ritual to revive them
- ### Persistent Player Presence
    - Npc player is spawned at a player logout spot that will sync its health / inventory with the player (Similar to
      the offline system in Rust (video game) ).
- ### No Player Nametags ✅
- ### Chat Supressor✅
    - Chat only shows
        - Your own messages
        - Admin/Server messages (/tellraw /say)
    - Chat should not show messages regarding other players:
        - Advancements
        - Death Messages
        - Join/Leave Messages
- ### Command Supressor✅
    - Non-OP players only get to use /help which shows a spooky message.
- ### Server Player List Hider ✅
    - When pinging the server, it returns a 0/1 player count
    - When in-game, the tab list shows no players
    - Hidden tab list shows a fancy header.
- ### No Nether Fast Travel.✅
- ### Random Spawns ✅
    - Players spawn at random locations in the world border if they dont have a spawn point.
- ### Config✅
    - Config json file will be available in the server directory's /config folder.
    - Config can be changed ingame with the /yolt command
    - Config options:
        - World Border Diameter
            - Overworld and Nether border sizes are linked as the scaling between the dimensions are removed.
            - End border size is scaled by the END_BORDER_SCALE config option. (Default: 2x the overworld/nether size)
        - World Border Center (Overworld and Nether border centers are linked).
            - Overworld and Nether border centers are linked as the scaling between the dimensions are removed.
            - End border center is always 0,0
