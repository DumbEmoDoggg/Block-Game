# World Generation

This document describes how the game world is generated procedurally.

---

## Overview

The world is an infinite grid of 16 × 16 × 128 **chunks**. When the player moves, new chunks are generated on-the-fly and old ones outside the render distance are unloaded.

Generation is split into three stages that run in order for each new chunk:

1. **WorldGenerator** — builds the base terrain shape and places surface blocks according to the biome.
2. **WorldFeatures** — optional decoration passes applied after base terrain (caves, ores, trees).
3. **BiomeProvider** — consulted by the generator to decide which surface block to use for each column.

---

## Terrain Shape

`DefaultWorldGenerator` uses `PerlinNoise` (seed `12345L`) to compute terrain height at every XZ position via `World.getTerrainHeight(wx, wz)`.

Three noise layers are combined:

| Layer | Frequency | Purpose |
|---|---|---|
| Continental | 0.003 | Large-scale elevation variation, shaped through `tanh` to create plateaus and cliffs |
| Hills | 0.008 | Mid-scale hill shapes layered on top of the continental base |
| Detail | 0.020 | Fine surface roughness |

The result is mapped to a height roughly in the range **[30, 100]** blocks above bedrock (y=0).

The ocean / sea level is at **y = 48**. Below that, columns are filled with water blocks.

### Chunk Contents (bottom to top)

| y range | Block |
|---|---|
| 0 | `BEDROCK` (unbreakable) |
| 1 – surfaceY−1 | `STONE` |
| surfaceY | Surface block chosen by biome (Grass / Sand / Snow) |
| surfaceY−1 | `DIRT` (3 layers under grass/snow) or `SAND` (under desert) |
| surfaceY+1 and above | `AIR` (or `WATER` if below sea level) |

---

## Biomes

`DefaultBiomeProvider` assigns a biome to each XZ column based purely on terrain height:

| Biome | Condition | Surface block |
|---|---|---|
| `SNOW` | surfaceY > 80 | `SNOW` |
| `PLAINS` | 50 ≤ surfaceY ≤ 80 | `GRASS` (dirt beneath) |
| `DESERT` | surfaceY < 50 | `SAND` |

To add a new biome, add an entry to `BiomeType`, update `DefaultBiomeProvider.getBiome()` with the relevant thresholds, and handle the new biome in `DefaultWorldGenerator` (and any `WorldFeature` that should behave differently per biome).

---

## World Features

Features are registered in `World`'s constructor and applied in order after base terrain generation:

```
CaveFeature  →  OreFeature  →  TreeFeature
```

### CaveFeature

Carves worm-like tunnels using two independent 3-D Perlin noise fields. A block is hollowed out wherever both noise values are simultaneously near zero.

* Carving starts at y=1 (bedrock at y=0 is never removed).
* The carving threshold is tapered near the surface to keep openings rare.
* Tunnels do not break through underwater floors.

### OreFeature

Replaces stone blocks with ores where a 3-D noise blob falls below a per-resource threshold. Each resource uses a separate noise offset so their distributions are independent.

| Resource | Max depth (y) | Relative rarity |
|---|---|---|
| Coal Ore | 55 | Most common |
| Iron Ore | 40 | Common |
| Gold Ore | 25 | Rare |
| Gravel patches | 60 | Common |

### TreeFeature

Places oak-style trees on `GRASS` surfaces in `PLAINS` biomes above sea level.

* Each column has a stable pseudo-random value; if it falls below **4 %** the column becomes a tree origin.
* Tree structure: 5-block wood trunk + rounded leaf canopy (5×5 lower, 3×3 upper).
* Cross-chunk canopy: each chunk scans a 2-block border so leaves from adjacent trees are placed correctly without cutting off at chunk boundaries.

---

## Adding a Custom World Feature

1. Create a class that implements `WorldFeature`:
   ```java
   public class MyFeature implements WorldFeature {
       @Override
       public void apply(Chunk chunk, World world) {
           // modify blocks in chunk using chunk.setBlock(lx, y, lz, type)
       }
   }
   ```
2. Register it in `World`'s constructor, after the existing features (or between them if order matters):
   ```java
   this.features.add(new MyFeature());
   ```

---

## Block Ticking

`World` maintains a double-buffered tick queue. Any `BlockBehavior` can schedule a tick for itself or a neighbour by calling `world.scheduleBlockTick(x, y, z)`. Ticks scheduled during a frame are deferred to the next frame to prevent order-dependent cascades.

Water flow (`WaterBehavior`) and falling blocks (`FallingBlockBehavior`) both use this mechanism.
