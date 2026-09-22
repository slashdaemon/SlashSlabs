# SlashSlabs — TBS integration (M5)

Dependency-ordered steps for adding SlashSlabs to The Block Survival's reset world. Nothing
here touches `Projects/TBS/` or the live server; each step needs the owner's go-ahead, and the
production deploy needs explicit approval.

## Inputs (decisions from `PLAN.md` §10)

| | Needed for | Recommendation |
|---|---|---|
| D1 target version | the jar | 26.1.2 (TBS today). SlashSlabs has jars for 26.2/26.3 too, so the decision can follow Geyser and SoulCraft |
| D2 pack policy | `auto-host.json` | Required (`"required": true`) |
| D3 footsteps | nothing to build | Accept server-played copper sounds (sound patcher, on by default) once someone has listened in-game |
| D4 step height | `slashslabs.json` | Owner's call; tested on vanilla clients, skip-on-Bedrock built in |
| D5 licence | public release only | — |
| D6 animal spawns | `slashslabs.json` | Vanilla (off) |

## Steps

1. **Pack entry.** SlashSlabs isn't on CurseForge or Modrinth yet, so it goes into
   `TBS-server` as a server-only override jar (`server-overrides/mods/slashslabs-<v>+mc26.1.2-fabric.jar`),
   noted in the pack CHANGELOG. It must not go into the client pack: clients need nothing.
2. **Configs** in `TBS-server/config/`:
   - `polymer/auto-host.json`: `{"enabled": true, "required": true, "type": "polymer:automatic",
     "message": "…"}`. Bloom.host has one allocation and no proxy, so same-port hosting fits.
   - `slashslabs.json`: defaults, plus `stepHeight` per D4.
3. **Contract lines.** Add to `TBS-mod-strategy.md` and TBS `CLAUDE.md`: *a server resource pack
   is required and auto-delivered; no client mod is.*
4. **BlueMap:** nothing to add; it reads SlashSlabs' models from the jar. After a pack change run
   `/bluemap purge <map>`.
5. **Rehearsal on this PC:** `python scripts/prodtest.py tbs --keep` runs the exact TBS server
   modset with the shipped jar. Scout seeds there (`/slashslabs survey`, fly with the vanilla
   client), then pre-generate the reset radius with Chunky. SlashSlabs costs about 0.5 ms/chunk
   under C2ME.
6. **Checks still owed on the real host** (see `TESTING.md`, open items): AutoHost through the
   TBS hostname/SRV record, and a Bedrock player via Geyser (sees copper slabs until M8).
7. **Deploy:** only with the owner's explicit approval.

## Removal later

`/slashslabs purge <radius>` over the explored area before removing the jar (see `README.md`).
