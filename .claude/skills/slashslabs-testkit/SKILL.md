---
name: slashslabs-testkit
description: >
  Run any tier of the SlashSlabs regression and interpret the result. Use when asked whether a
  change broke anything, to run the release gate before a build is published, to re-run the
  self-test / prodtest / determinism / vanilla-client checks, to prepare SlashSlabs for TBS
  integration or a Modrinth/CurseForge release, or when a test script (devserver.py, prodtest.py,
  determinism.py, survey.py, client.py, regress.py, clienttest.py) misbehaves. Covers which tier a
  given change actually needs, the ordering rules that make the harness safe to run back to back,
  and what each failure signature means. Single machine (this PC); the server tiers are
  unattended, the client tier is not.
---

# SlashSlabs testkit

One script front-ends everything: **`python scripts/regress.py <verb>`**. It drives the existing
per-purpose scripts in a safe order, asserts the server's real config, cleans up between tiers,
and writes `build/regression/<timestamp>/report.md`.

```bash
python scripts/regress.py doctor           # preflight, no side effects — run this first, always
python scripts/regress.py quick            # unit + one band self-test          (~5 min)
python scripts/regress.py bands            # buildAll + unit + self-test x3
python scripts/regress.py gate             # buildAll + prodtest on shipped jars (the long one)
python scripts/regress.py props            # determinism + cost
python scripts/regress.py full [--client]  # bands -> gate -> props [-> client]
```

`--reset-config` and `--kill-stale` let `doctor` fix what it finds. `--no-tbs` drops the TBS
modset target from the gate. Exit 0 only if every check passed.

## The ladder — and what each rung is blind to

| Tier | What runs | What it can see | What it **cannot** see |
|---|---|---|---|
| `unit` | `:versions:<b>:test` | rise detection, colour maths, palette fitting | anything needing a world |
| `selftest` | `/slashslabs selftest` on a dev server — 399 checks | smoothing output, idempotence, Polymer backing + collision for every state, placement, drops, recipes, tags, grass spread/pull/decay, purge | **packaging**, and **whether the worldgen hook is even enabled** (see below) |
| `gate` | `prodtest.py` — the *shipped* jars on real Fabric servers + the full TBS 1.5.0 modset | nested-jar packaging, real mod-loader conflicts, unsafe worldgen reads | client-side anything |
| `props` | `determinism.py` | generation-order independence, ms/chunk cost | correctness |
| `client` | `clienttest.py` driving a Prism client | movement, mining feel, placement over the network, textures, LODs | sounds, vehicles, ping, Bedrock |

**The headline: a dev run cannot see a packaging bug.** The first `prodtest` run caught exactly
that — `polymer-sound-patcher` declares a dependency on `polymer-resource-pack-extras` in its
`fabric.mod.json` but not its POM, Fabric Loader silently drops a nested mod whose dependency is
missing, and startup died with `NoClassDefFoundError`. Nothing short of the gate would have found
it. **Never publish on a green `bands` alone.**

## Rules that keep the harness honest

1. **The self-test cannot detect a disabled worldgen hook.** `WorldOps.smooth` calls
   `TerrainSmoother.apply` directly and never consults `cfg.worldgen`; the gate exists only in the
   two hooks (`TerrainSmoother.java:84`, `:98`), and `SelfTest.java:88` goes through `WorldOps`. So
   `/slashslabs selftest` happily reports **399/399 with worldgen off** — verified 2026-09-22:
   `devserver.py --band 26.1.2 --fresh --config worldgen=false` prints
   `worldgen off | … 0 chunks smoothed, 0 blocks placed` and then `SELFTEST PASS 399/399`.
   `regress.py` therefore runs `slashslabs info` as the first RCON command of every server tier and
   fails on a mismatch. If you drive `devserver.py` by hand, **read the `info` line yourself.**
2. **`determinism.py` leaves the config poisoned.** Its last pass writes `worldgen=false` into
   `versions/26.1.2/run/config/slashslabs.json`, `devserver.py` only rewrites that file when
   `--config` is passed, and `SlabsConfig.load()` writes the value back on every boot. Delete the
   file (or `regress.py doctor --reset-config`) to get defaults back. It also leaves `det-a`/`det-b`/
   `det-c`/`det-off` worlds behind; `regress.py props` sweeps them.
3. **`--fresh` can silently no-op.** `devserver.py` deletes the world with
   `rmtree(ignore_errors=True)`, which does nothing while Windows still holds the world's file
   handles — the stale world is then reused and the tier reports a **false pass**. `regress.py`
   asserts the directory is actually gone. By hand, check it.
4. **Never run Gradle in this checkout while a dev server is up** — it can kill the daemon. Tiers
   are strictly serial for this reason.
5. **Two port pairs, no collision.** Dev 25580 (game) / 25581 (RCON); prodtest 25595 / 25596. A dev
   server and a prodtest server can coexist; two dev servers cannot.
6. **`prodtest` downloads.** First `tbs` run pulls the whole TBS modset from Modrinth; re-runs are
   near-offline because `fetch()` caches by path. `doctor` reports which targets are cached.

## What to run for what you changed

| Touched | Run | Why |
|---|---|---|
| `color/` (ColorMath, PaletteFit, GrassColors), `grassPalette` | `quick` + the tint snaps | pure maths plus a look at seams |
| `worldgen/RiseDetector`, `MaterialMap` | `bands` + `props` | placement decisions and order-independence |
| `worldgen/TerrainSmoother`, `mixin/`, `hookStage` | `bands` + `props` + `gate` | the LIGHT-step neighbour rules differ per band |
| **`block/ModBlocks` slot order, Polymer backing** | **`gate` + `client`, mandatory** | slot order moves materials to other backing states and **invalidates clients' Voxy LODs** |
| `block/*Block` behaviour (spread, decay, drops) | `quick` | the self-test covers these exhaustively |
| `pack/PackGenerator`, `resources/assets/` | `gate` + `client` | models and textures only exist for a real client |
| `compat/loot-*` | `bands` | a 26.1-format loot table loads on 26.3 with its conditions **silently dropped** |
| `command/`, `ops/` | `quick` | |
| `build.gradle`, `gradle/band.gradle`, Polymer version | `full` including `tbs` | packaging and nesting live here |
| nothing — preparing a release | `full` | |

## Reading a failure

| Signature | Means |
|---|---|
| `SELFTEST FAIL k/n` | the failing check names itself in the preceding `FAIL …` lines — read those, not the count |
| `NoClassDefFoundError` at prodtest startup | a nested Polymer module is missing a POM dependency and Loader dropped it silently |
| `unsafe terrain read` / `setBlock in a far chunk` | the LIGHT-step region wrote outside the centre chunk, or read a neighbour without going through its chunk (26.2+ logs this) |
| determinism below ~95 % | a real ordering regression |
| determinism 95–98 % | expected — vanilla's own tree and leaf-litter placement is order-dependent; 21 % of surface columns differ with SlashSlabs **off** |
| prodtest passes, dev self-test fails | a config or world-state problem, not a code one — check the `info` line and that `--fresh` really deleted the world |
| client joins then immediately disconnects | the AutoHost pack was declined, or registry sync rejected the client (a TBS client-pack matter, not ours — SlashSlabs' Polymer entries are hidden) |

Recorded baselines to compare against, from `docs/TESTING.md`: self-test **399/399** on a dev
server and **398/398** under `prodtest` (the dev run has one check more — expect the pair, not one
number, and treat a change in *either* as a regression);
worldgen blocks in 11×11 chunks **3339** (26.1.2, 26.2), **3337** (26.3), **3345** (tbs); hook cost
**0.9–1.2 ms/chunk**, **0.44 ms** under C2ME; determinism **97.1 %** rows-vs-reverse and **97.9 %**
rows-vs-shuffle.

## The client tier

```bash
# terminal 1 — leave it running
python scripts/devserver.py --band 26.1.2 --fresh --keep --autohost -c "slashslabs info"
# terminal 2
python scripts/clienttest.py --band 26.1.2
```

**It drives the real keyboard and mouse.** `client.py` refuses to send input unless the Minecraft
window holds the foreground, so nothing else can use the PC while it runs — this tier is attended
only, which is why `full` excludes it unless you pass `--client`.

Asserted checks (machine pass/fail): join and pack download, walking the smoothed fixture versus the
unsmoothed control, sprint without movement rejections, the step-height attribute across death and
respawn, mining at grass speed rather than copper speed, and placing a slab then completing it into
a vanilla block. Expectations are derived from `TestField.PROFILE`, so changing the fixture moves
them automatically.

Visual checks write PNGs to `--out` and print their paths — **read the images**: the held item's
name and icon, real waxed-copper slabs rendering as copper (R12), and tint seams across the six
strips.

Mechanics worth knowing before editing `clienttest.py`:

- Block assertions use `execute if block`. `data get block` needs a `BlockEntity`, which a slab has
  not, so it only ever returns `ERROR_NOT_A_BLOCK_ENTITY`.
- `devserver.py` sets `gamemode=creative`, where left-click breaks instantly — the mining check
  switches to survival and switches back.
- Aim is set server-side with `/tp <player> x y z <yaw> <pitch>` and the click is sent with
  `client.py mousehold`, which deliberately does **not** move the cursor: the game grabs the mouse,
  so moving it would turn the camera and throw the aim away.
- The fixture is placed with `execute positioned …`, because the bare `/slashslabs test-field`
  builds at the command source's position + (2,0,2) and RCON sits at world spawn. (This is a
  different field from the one `SelfTest` builds, which is pinned at 1000,·,1000.)
- The player name is discovered from RCON `list` — never hardcode it. `biome_view.py` still
  hardcodes `slashdaemon`, so pass it a matching account or fix it first.
- Voxy LOD checks need the other instance: `--instance SlashSlabs-Voxy-26.1.2`. Prism must be
  restarted before it notices a newly created instance folder. The AutoHost pack prompt appears on
  quick-play join; Proceed is at roughly (400, 488) in snap space.

## What none of this covers

Do not report "full regression passed" as though it means full coverage. These still need a person,
and they are the open items in `docs/TESTING.md`:

- **Sounds** (D3) — break/place/step/fall through the sound patcher, heard by actor and observer.
  The code path is in place; nobody has listened.
- **Sneak at slab edges** — injected keys slow the player but do not trigger vanilla's edge stop,
  on vanilla slabs and full blocks alike. Needs a real keyboard.
- **Boats, horses, minecarts** over slabs.
- **Mining feel at 100+ ms ping** (mining is server-side).
- **Tint seams** in savanna, swamp, jungle and badlands edges — judged by eye, not only by ΔE.
- **Bedrock via Geyser** (M8) — Bedrock players currently see copper slabs.
- **AutoHost through the real TBS hostname / SRV record** on Bloom.host.

## Recording results

`build/regression/<ts>/report.md` is shaped like the tables in `docs/TESTING.md` so numbers paste
straight across. When a run changes a recorded baseline, update the matching table in
`docs/TESTING.md` and say which run it came from. `build/` is gitignored — the report is evidence
for the session, not an artifact to commit.

Standing rule from the repo: **never deploy to production without explicit approval.** Preparing a
release is fine; touching the live server is not.
