# Store listing — CurseForge / Modrinth

## Projects

- **CurseForge:** project 1709433 (authors.curseforge.com/#/projects/1709433).
- **Modrinth:** slug `slashslabs` (being set up).

## Text

- **Summary:** Server-side half-slab steps on every one-block terrain rise, so players walk the
  world without jumping — no client mod needed.
- **Description:** `description-modrinth.md` (tables) and `description-curseforge.md` (the same
  text with the table as a list). Keep them in step with `README.md` and `CHANGELOG.md`.

## Images

Selected set for the 0.1.0 listing. Captured on a `devserver.py --band 26.1.2 --fresh` world,
worldgen smoothing on, from the vanilla Prism client (`SlashSlabs-Vanilla-26.1.2`).

| File | What it shows |
|---|---|
| `01-with-slashslabs-snowy-taiga.png` | Snowy taiga shore with smoothing on: half-steps feather the grass slope and the snow shoreline |
| `02-vanilla-same-camera.png` | The identical camera with the slabs purged — same hill in whole blocks |
| `03-landscape-snowy-shore.png` | Wider establishing shot of the same shore, for visual consistency with the pair |

`01` and `02` are the same camera position, the same in-game time and the same weather. Only the
mod differs, so the comparison cannot be accused of a flattering angle.

## Re-capturing

The dev world seed is fixed — two separate `--fresh` worlds both put spawn in `frozen_ocean` and
the nearest plains at `736, -528` — so these coordinates reproduce.

```
01 / 02   camera  113  71   95   yaw -45  pitch 21     (subject: taiga at 129, 111, surface y=64)
03        camera -241 101   95   yaw -45  pitch 24     (subject: snowy plains at -192, 144, surface y=71)
```

Conditions: `gamemode spectator`, `time set 6000`, `weather clear`, HUD hidden with F1.
Spectator matters — no hand, no hotbar in frame.

For the B frame, purge at the camera and smooth straight back:

```
execute at <player> run slashslabs purge 3
execute at <player> run slashslabs smooth 3
```

**`radius` is in chunks, not blocks.** Radius 3 is 7×7 chunks and completes instantly. Radius 64
is 129×129 chunks — it moves ~61k blocks, freezes the server for minutes, and drops the client to
the title screen. `02` in this set was captured under an oversized purge before that was
understood; the framing is unaffected, but use radius 3 when re-taking.

## Not captured

Camera framing is scripted, so it fails in ways worth knowing: the camera must be verified in open
air **and** raycast to the subject, or it lands inside a hill, inside a tree canopy, or above the
treetops. Rejected examples and their causes are in the contact sheet.
