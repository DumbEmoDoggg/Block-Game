# Rendering

This document describes the rendering pipeline, shaders, texture atlas, clouds, particles, and HUD.

---

## Pipeline Overview

`Renderer` drives the entire frame. Passes execute in this order each frame:

1. **World geometry** — chunk meshes with texture atlas, face lighting, and distance fog.
2. **Block highlight** — wireframe outline on the targeted block.
3. **Clouds** — noise-based volumetric cloud mesh rendered at y=100.
4. **Water surface** — semi-transparent water faces rendered with alpha blending.
5. **Mobs** — textured mob models (`MobRenderer`).
6. **Particles** — billboard quads for block-break effects (`ParticleSystem`).
7. **Underwater overlay** — full-screen blue quad when the player's eyes are submerged.
8. **HUD** — crosshair, hotbar, hearts.

---

## Shaders

All GLSL shaders live in `src/main/resources/shaders/`.

| Shader pair | Used for |
|---|---|
| `vertex.glsl` / `fragment.glsl` | World geometry (chunks, block highlight) |
| `hud_vertex.glsl` / `hud_fragment.glsl` | Hotbar and heart textures |
| `icon_vertex.glsl` / `icon_fragment.glsl` | Tinted quads (crosshair, underwater overlay); exposes a `uColor` vec4 uniform |
| `cloud_vertex.glsl` / `cloud_fragment.glsl` | Cloud geometry |

### Fragment Shader (world geometry)

* Performs an **alpha test**: fragments with `texColor.a < 0.5` are discarded. This enables leaves and other transparent blocks to have hard-edged cut-outs without blending.
* Applies **face lighting** multipliers before fog.
* Applies **linear distance fog** to blend geometry into the sky colour at the render horizon.

---

## Texture Atlas

`TextureAtlas` builds a single GPU texture at startup by packing all block tile PNGs into a grid:

* Each tile is `16 × 16` pixels.
* The atlas is arranged in a fixed number of columns and rows.
* The JSON file `src/main/resources/textures/Blocks/block_textures.json` maps tile key strings (e.g. `grass_top`, `stone`) to PNG filenames.
* `TextureAtlas.getTileId(BlockType, isTop, isSide)` returns the tile index for a given block face.
* `TextureAtlas.getUV(tileIndex)` returns `(u0, v0, u1, v1)` atlas coordinates.

If a PNG is missing or fails to decode, the tile falls back to a procedurally generated placeholder.

---

## Chunk Meshes

`ChunkMesh` builds a VAO/VBO for each chunk. For each non-air block, it emits the faces that border a transparent or non-solid neighbour (visible faces only). Each vertex stores:

* 3D position
* UV coordinates (from the texture atlas)
* Face lighting multiplier

Meshes are rebuilt lazily whenever a chunk's `dirty` flag is set (e.g. when a block is placed or broken).

---

## Clouds

`CloudRenderer` generates a flat 3-D mesh at **y = 100** each frame using a 2-D Perlin noise field (seed `9876543L`, scale `0.065`):

* A noise cell above the threshold (`0.0`) becomes a cloud box.
* Clouds scroll in the **+X direction** at **3 units/second**.
* The cloud grid is centred on the player position so it appears infinite.
* Cloud boxes are **12 × 4 × 12** world units.

---

## Particle System

`ParticleSystem` spawns **12 billboard quads** per block break, totalling up to **500 simultaneous particles**.

* Each particle has a colour sampled from the broken block's RGB with a small random variation.
* Particles are affected by gravity (−20 m/s²) and have a randomised lifetime around **0.9 seconds**.
* No world collision — particles fall through geometry for simplicity and negligible cost.
* Particles are axis-aligned billboards (always face the camera).

---

## Underwater Rendering

When the player's eye position is inside a water block:

* A full-screen tinted quad (blue, alpha 0.40) is drawn using the icon shader.
* Fog parameters switch to underwater values:

| Parameter | Value |
|---|---|
| Fog colour R/G/B | 0.0 / 0.18 / 0.38 |
| Fog start | 0.5 blocks |
| Fog end | 8.0 blocks |

---

## HUD

The HUD is drawn in screen space after all world rendering.

| Element | Details |
|---|---|
| Crosshair | White plus sign centred on screen |
| Hotbar | 8-slot crop of `hotbar.png`; selected slot is highlighted; block icons drawn on top |
| Heart bar | 10 hearts (`full.png` / `half.png`) left-aligned above the hotbar |

HUD rendering uses `GUI_SCALE = 2` so elements are pixel-perfect on a 1280×720 window.
