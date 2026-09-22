# SlashSlabs — M2 surface survey and slot allocation

Companion to `PLAN.md` §3 and M2. Reproduce with a dev server started with `worldgen=false`
and `python scripts/survey.py 5` (one 11×11-chunk area in each of 14 biomes, seed `4242`, MC
26.1.2 vanilla worldgen). Raw data: `versions/26.1.2/run/slashslabs/survey-*.json`.

## Rise-edge surface materials

135,966 one-block-rise columns across plains, forest, birch forest, taiga, savanna, jungle,
swamp, badlands, dark forest, meadow, cherry grove, sunflower plains, windswept hills and
old-growth spruce taiga.

| Surface block at the rise | Share |
|---|---|
| grass_block | 59.7% |
| dirt | 10.3% |
| sand | 9.1% (mostly under water; 4.4% of placements) |
| gravel | 6.6% (mostly river and ocean beds; skipped) |
| stone | 5.5% |
| red_sand | 2.4% |
| podzol | 1.6% |
| snow_block | 1.5% |
| everything else | < 1% each |

What smoothing places, after the guards (water depth, plants, structures, snowy biomes):

| Placed | Share |
|---|---|
| grass slab (all tints) | 68.1% |
| snow layers | 12.1% |
| stone slab | 5.9% |
| dirt slab | 5.4% |
| smooth sandstone slab (sand) | 4.4% |
| smooth red sandstone slab (red sand) | 3.3% |
| other vanilla slabs | < 1% |

## Slot allocation

The four slots go to **dirt + three grass tints**. Grass carries two thirds of all placements,
so extra greens buy the most visual quality. Dirt covers the dirt family (12%), for which vanilla
has no lookalike slab. Sand (4.4%) reads well enough as smooth sandstone, so a real sand slab
stays optional (`sandSlab: true` trades one grass tint for it).

## Grass palette

67,439 grass-slab columns sampled with the client's 5×5 biome blend. Error is CIE76 ΔE between
the palette entry used and the colour real grass has there. ΔE < 5 is barely visible on a noisy
texture, and ΔE > 10 is an obvious seam.

| Palette | Mean ΔE | Columns ΔE > 5 | Columns ΔE > 10 |
|---|---|---|---|
| Plan default: plains #91BD59, forest #79C05A, savanna #BFB755 | 8.39 | 47.5% | 29.4% |
| **Chosen: forest #79C05A, birch #88BB67, taiga #86B783** | **4.68** | **28.3%** | **11.3%** |
| forest, birch, savanna | 6.54 | 40.8% | 23.8% |
| forest, plains, taiga | 5.03 | 35.0% | 16.7% |

The chosen palette is the weighted k-medoids fit (k=3) of the samples, snapped to the vanilla
biome colours it landed on. The residual seams (RISK R4) fall on the far-off greens: savanna and
badlands yellows, swamp and mangrove olives, jungle's saturated green, dark forest. Servers
dominated by one of those can refit with `/slashslabs survey` (it prints the k=1..3 fit for the
surveyed area) and set `grassPalette`.
