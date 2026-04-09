# Blocks

This document covers all block types, their properties, behaviors, and how to add new ones.

---

## Block Types

All blocks are defined in the `BlockType` enum. Each entry stores:

| Field | Type | Description |
|---|---|---|
| `id` | `int` | Unique compact id stored in chunk byte arrays (max 255 types) |
| `r`, `g`, `b` | `float` | Base RGB colour used as a fallback tint when no texture is available |
| `solid` | `boolean` | Whether the block has collision geometry and culls adjacent faces |
| `transparent` | `boolean` | Whether adjacent faces should still be rendered (leaves, water, glass) |
| `breakable` | `boolean` | Whether a player can break this block (`false` for Bedrock) |
| `behavior` | `BlockBehavior` | Optional lifecycle callbacks (null for most blocks) |

### Complete Block List

| Block | ID | Solid | Transparent | Breakable | Behavior |
|---|---|---|---|---|---|
| `AIR` | 0 | No | — | Yes | — |
| `GRASS` | 1 | Yes | No | Yes | — |
| `DIRT` | 2 | Yes | No | Yes | — |
| `STONE` | 3 | Yes | No | Yes | — |
| `WOOD` | 4 | Yes | No | Yes | — |
| `LEAVES` | 5 | Yes | Yes | Yes | — |
| `SAND` | 6 | Yes | No | Yes | `FallingBlockBehavior` |
| `SNOW` | 7 | Yes | No | Yes | — |
| `PLANKS` | 8 | Yes | No | Yes | — |
| `WATER` | 9 | No | Yes | Yes | `WaterBehavior` |
| `BEDROCK` | 10 | Yes | No | **No** | — |
| `GRAVEL` | 11 | Yes | No | Yes | `FallingBlockBehavior` |
| `COAL_ORE` | 12 | Yes | No | Yes | — |
| `IRON_ORE` | 13 | Yes | No | Yes | — |
| `GOLD_ORE` | 14 | Yes | No | Yes | — |

---

## Block Behaviors

`BlockBehavior` is an interface with three callback methods:

```java
void onPlace(World world, int wx, int wy, int wz);
void onBreak(World world, int wx, int wy, int wz);
void onTick (World world, int wx, int wy, int wz);
```

### FallingBlockBehavior

Used by `SAND` and `GRAVEL`. On each tick, if the block below is non-solid (air, water, etc.), the block moves down by one. The fall continues on subsequent ticks until the block lands on a solid surface.

### WaterBehavior

Used by `WATER`. On each tick, water checks the six adjacent positions and flows outward into non-solid, non-water blocks. Flow is limited to horizontal and downward directions. Ticks are scheduled for any block adjacent to a newly placed or removed block so the fluid simulation propagates naturally.

---

## Transparency and Face Culling

The renderer skips (culls) a face between two adjacent blocks only when the neighbour is fully opaque. The `isTransparent()` method in `BlockType` returns `true` for any block that is either non-solid or has the `transparent` flag set. A face bordering such a block is always emitted.

This means:
* `LEAVES` is both solid (collision) and transparent (adjacent faces are still rendered, and the texture contains see-through pixels discarded by the fragment shader's alpha test).
* `WATER` is non-solid and transparent — you can see through it and walk through it.

---

## Textures

Block textures are loaded from PNG files under `src/main/resources/textures/Blocks/`. The mapping from tile keys to filenames is read from `block_textures.json` in the same directory at startup.

Each block face can have a different tile. The `TextureAtlas` builds a GPU atlas at startup and exposes UV coordinates via `getUV(tileIndex)`.

### Adding a Texture for a New Block

1. Place the PNG(s) in `src/main/resources/textures/Blocks/`.
2. Open `block_textures.json` and add entries for the tile keys your block will use (e.g. `my_block_top`, `my_block_side`, `my_block_bottom`).
3. In `TextureAtlas`, add `TILE_*` constants and handle the new keys in `getTileId()`.

---

## Adding a New Block Type

1. **Add the enum entry** in `BlockType.java`:
   ```java
   MY_BLOCK (15, 0.5f, 0.3f, 0.2f, true, false),
   ```
   Pass a `BlockBehavior` instance as the fifth argument if the block needs special behavior.

2. **Add textures** — see the Textures section above.

3. **(Optional) Add to the hotbar** — if you want the block to be placeable by the player, add it to the `HOTBAR` array in `Player.java`.

4. **(Optional) Generate it in the world** — place it in an existing `WorldFeature` or write a new one (see [World Generation](world-generation.md)).
