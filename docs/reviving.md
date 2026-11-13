# Reviving someone

Craft a grave head together with a nether star, a netherite ingot, a diamond, a gold ingot and an iron ingot, in any
slots of a crafting table (six items don't fit the inventory's 2x2 grid). The result is a paper called "Revive <name>".
Right-click with it while that player is locked out and their lockout ends ("<name> can come back now."). One paper is
used up. If the player isn't locked out you get "<name> is not dead." (or "is not locked out." with the lockout off) and
nothing is used. A revived player comes back with nothing, at a random spot.

A revive paper shows a purple name, lore and the enchantment glint. With `resourcePack.reviveTexture` on and the
server's resource pack set up, it shows a texture of its own instead of plain paper. See
[revive paper texture](hardcore.md#revive-paper-texture).

With the hardcore [Curse of the Undead](hardcore.md#curse-of-the-undead) on, a revived player still loses a max heart
for the death. A revive paper gives hearts back only if `hardcore.curse.reviveRefundHearts` is above 0.

Grave heads can't get Curse of Vanishing in an anvil, and a grave head or revive paper that has it anyway still goes
into the grave.

The recipe is `data/you-only-live-twice/recipe/revive.json`, so a datapack can replace it with your own.

Settings: [`revive`](config.md#revive), [`resourcePack`](config.md#resourcepack).

[All pages](README.md)
