# You Only Live Twice

A minecraft server-side fabric mod intended to increase the stakes of a multiplayer minecraft playthrough by removing
several features usually taken for granted and provide an overall more immersive experience.

# Usage

The focus of this mod is to maximize immersion by relying on in-game experiences as much as possible. Third party
communication tools, and F3 are not reccomended to be used while playing this mod.

This mod is intended to be used on its own with a few exceptions listed below. The addition of other mods might cause
issues.

Some addons I highly recommend to use along with this are:

- [SimpleVoiceChat (Client/Server Mod)](https://modrinth.com/project/9eGKb6K1)
    - This is the main mod i originally intended to use this mod with for an SMP.
- [Chunky (Server-side Mod)](https://modrinth.com/project/fALzjamp)
    - Since the world border is so small, Chunky can be used to basically prerender the whole world.
- [Hardcore Hearts (Resource Pack)](https://modrinth.com/project/5AMcQaG0)
    - Just a simple resource pack that changes the hearts to hardcore hearts to make the
- [No F3 (Client-side Mod)](https://modrinth.com/project/MPFUV2Ds)
    - Highly reccomend to remove F3 to ensure a more immersive experience where you dont rely on coords.

Upon starting the server, a config file will be created in the run/config folder. And the world borders will be set to a
diameter of 10000 by default. The config can be changed in the file before starting the server or by using the /yolt
command in-game.

Since this is a server sided mod, all config is done through the /yolt command.

# Default Config

World Diameter : 10000 Blocks

Dimension Scaling : 1 across all dimensions

Player Respawn Cooldown : 2 weeks.

# Planned Features

- ### Pseudo Hardcore
    - Dead players are banned from the server for a period of time before they can join back.
    - ### Grave System
        - Players leave a grave with their items and their player head in it.
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
