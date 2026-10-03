# ReforgedFrost

Paper plugin (1.21.4+, Java 21) that adds the **Reforged Frost** armor as a real, animated 4-piece set.
Players need Minecraft **1.21.11+** (the model uses free-angle cube rotation) and server resource packs enabled.
No MythicArmors needed.

## How the set is rendered (hybrid)
- **Base armor** (helmet shell, chest, arms, belt, legs, boots) is baked into the vanilla armor textures by
  `tools/build_pack.py`. The game animates it, so it syncs with every pose and shows in the inventory preview.
- **3D extras** (spikes, horns, pauldrons, floating dust) cannot be drawn by vanilla, so `ArmorRig` shows them as one
  display entity per body part, mounted on the player, and animates them every tick:
  arms and legs swing while moving, the main-hand arm swings when you attack, the upper body leans while sneaking,
  and the dust orbits and bobs. Tune or turn off under `animation` in `config.yml`.
- Swimming, gliding and sleeping hide the 3D extras (the base armor stays).
- `/frost view` or **sneak + F** switches between first-person view (helmet, chest, arms hidden from you so they do not block the camera, default) and third-person view (everything visible). Remembered per player. The server cannot detect your camera, so this is manual.
  Others always see everything.

## Build (GitHub Actions)
Push to a repo. Every push regenerates the pack, builds the jar and publishes `ReforgedFrost.jar` + `pack.zip`
to the **Releases** page as `latest`. Local: `python3 tools/build_pack.py` (needs Pillow), then `gradle build` (Java 21).

## Install
1. Put `ReforgedFrost.jar` in `plugins/` and start the server.
2. The pack is sent on join. By default players download it from the GitHub release (`pack.external-url`, repo must be
   public, no port needed). To self-host, blank `external-url` and open `pack.port` (8123).
3. `/frost give <player>` and wear the pieces.

## Commands (perm `reforgedfrost.admin`, default op)
- `/frost give <player> [leather|netherite] [set|helmet|chestplate|leggings|boots]`
- `/frost pack [player]` resend the pack
- `/frost view` or **sneak + F** switches between first-person view (helmet, chest, arms hidden from you so they do not block the camera, default) and third-person view (everything visible). Remembered per player. The server cannot detect your camera, so this is manual.
- `/frost status` shows what is wrong if the texture is missing
- `/frost reload`

## Set bonus
- Any piece worn: Speed I
- All 4 pieces worn: Speed II and **+5% max health** (21 HP instead of 20)
- Tune in `config.yml` under `set-bonus` (`full-health-bonus`, 0 = off).

## Other resource packs
The pack is sent under its own fixed id and never clears other packs, so it coexists with Nexo/Oraxen/ItemsAdder.
The pack contains no shaders.

## Legacy MythicArmors mode
Off by default. `install-model: true` copies the model into MythicArmors and `pack.use-mythicarmor: true` serves its pack.
Do not combine it with the built-in renderer, the armor would be drawn twice.
