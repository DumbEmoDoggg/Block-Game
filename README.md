# Block Game

A Minecraft-inspired block-building game written in **Java 17** using [LWJGL 3](https://www.lwjgl.org/) for OpenGL 3.3 rendering.

---

## Features

| Feature | Details |
|---|---|
| 3-D block world | 16 × 16 × 128 chunks, rendered with OpenGL 3.3 core profile |
| Procedural terrain | Multi-octave Perlin noise (fBm) with continental shaping — smooth hills, valleys, and snow-capped peaks |
| Biomes | Plains, Desert, and Snow biomes determined by terrain elevation |
| Block types | 14 placeable block types plus Air and Bedrock (see [Blocks](docs/systems/blocks.md)) |
| Underground features | Cave tunnels, ore veins (Coal, Iron, Gold), and gravel patches generated procedurally |
| Trees | Oak-style trees spawn naturally on grass in Plains biomes |
| Water & swimming | Placeable water blocks with fluid-flow simulation; reduced gravity and swim-up controls when submerged |
| Mobs | 6 mob types (Zombie, Skeleton, Creeper, Spider, Pig, Sheep) rendered with textured models |
| Clouds | Perlin-noise volumetric clouds that scroll slowly overhead |
| Block interaction | **Left-click** to break a block (spawns particles) · **Right-click** to place one |
| Texture atlas | All block faces use PNG textures loaded from a configurable JSON atlas |
| Face lighting | Top = brightest · north/south = medium · east/west = darker · bottom = darkest |
| Underwater effects | Blue tint overlay and fog applied when the player's eyes are submerged |
| Health | 10-heart health bar shown on the HUD |
| Hotbar | 8 slots — keys **1–8** or scroll wheel to cycle; displayed at the bottom of the screen |
| Crosshair | Always-visible white crosshair |
| Block highlight | Targeted block is outlined with a white highlight |
| Save / Load | Press **Enter** to save the world and player position to `~/.blockgame/world.dat`; loaded automatically on next startup |

---

## Controls

| Key / Button | Action |
|---|---|
| W A S D | Walk |
| Space | Jump / swim upward |
| Left Ctrl | Sprint |
| Mouse | Look around |
| Left-click | Break block |
| Right-click | Place selected block |
| 1 – 8 | Select hotbar slot |
| Scroll wheel | Cycle hotbar slot |
| Enter | Save world & player position |
| Escape | Toggle mouse cursor (pause look) |

---

## Building

**Prerequisites:** JDK 17+ (includes `jpackage`), Apache Maven 3.6+

```bash
# Clone the repository
git clone https://github.com/DumbEmoDoggg/Block-Game.git
cd Block-Game

# Compile, run tests, and produce both the fat JAR and Windows EXE
mvn package
```

Artefacts produced in `target/`:

| File | Description |
|---|---|
| `block-game-1.0.0-fat.jar` | Self-contained JAR (all native libraries included) |
| `BlockGame.exe` | Windows executable wrapper — requires Java 17+ on the target machine |
| `BlockGame/` | Self-contained app image — includes a bundled JRE, **no Java install required** |

Run the unit tests on their own with:
```bash
mvn test
```

---

## Running

### Auto-updating launcher (recommended)

The easiest way to play.  Download **once**, and the launcher keeps your game up to date automatically — no need to re-download the artifact after every update.

1. Download `BlockGameLauncher.bat` from the [**latest-build** release](https://github.com/DumbEmoDoggg/Block-Game/releases/tag/latest-build) into any folder (even one with spaces in the path).
2. Double-click `BlockGameLauncher.bat`.
   * If `BlockGameLauncher.ps1` is not in the same folder it is downloaded automatically.
   * On first run the game is downloaded into a `BlockGame\` sub-folder.
   * On subsequent runs the launcher checks for a newer build; if one exists it is downloaded before the game starts.
3. A launcher window opens showing download progress.  Click **Play** when it is ready.

> **Requirements:** Windows 10 or later (PowerShell 5.1 is built in).  The game bundle includes its own JRE so no Java install is needed.

The launcher files are also committed to the repository under `launcher/` if you prefer to clone instead of downloading them from the release.

---

### Manual options

**Self-contained bundle (no Java required):**

Download and extract the `BlockGame-windows-bundled` artifact (from the CI), then run:
```
BlockGame\BlockGame.exe
```

**Cross-platform (fat JAR):**
```bash
java -jar target/block-game-1.0.0-fat.jar
```

**Windows (requires Java 17+):**
Double-click `BlockGame.exe` (Java 17 or later must be installed and on the `PATH` or `JAVA_HOME`).

---

## Project Structure

```
src/
├── main/
│   ├── java/com/blockgame/
│   │   ├── Main.java                     # Entry point
│   │   ├── Game.java                     # Game loop, GLFW window, save/load
│   │   ├── GameSystem.java               # Interface for pluggable game systems
│   │   ├── Saveable.java                 # Interface for save/load components
│   │   ├── world/
│   │   │   ├── BlockType.java            # Block type enum (id, color, flags, behavior)
│   │   │   ├── BlockBehavior.java        # Interface: onPlace / onBreak / onTick
│   │   │   ├── FallingBlockBehavior.java # Gravity for Sand & Gravel
│   │   │   ├── WaterBehavior.java        # Fluid-flow simulation
│   │   │   ├── Chunk.java                # 16×16×128 chunk data
│   │   │   ├── World.java                # Infinite chunk grid + tick system + save/load
│   │   │   ├── WorldGenerator.java       # Interface for terrain generation
│   │   │   ├── DefaultWorldGenerator.java# fBm Perlin terrain + biome surfaces
│   │   │   ├── WorldFeature.java         # Interface for post-generation decoration passes
│   │   │   ├── CaveFeature.java          # Cave carving pass
│   │   │   ├── OreFeature.java           # Ore vein and gravel placement
│   │   │   ├── TreeFeature.java          # Oak tree placement
│   │   │   ├── BiomeProvider.java        # Interface for biome lookup
│   │   │   ├── DefaultBiomeProvider.java # Height-based biome assignment
│   │   │   ├── BiomeType.java            # PLAINS / DESERT / SNOW enum
│   │   │   └── PerlinNoise.java          # Multi-octave Perlin noise (fBm)
│   │   ├── player/
│   │   │   ├── Camera.java               # View/projection matrices
│   │   │   └── Player.java               # Movement, physics, swimming, block interaction, health
│   │   ├── rendering/
│   │   │   ├── Shader.java               # GLSL shader loader/compiler
│   │   │   ├── TextureAtlas.java         # Block texture atlas builder
│   │   │   ├── ChunkMesh.java            # Per-chunk VAO/VBO mesh builder
│   │   │   ├── CloudRenderer.java        # Noise-based scrolling clouds
│   │   │   ├── MobRenderer.java          # Textured mob model renderer
│   │   │   ├── ParticleSystem.java       # Block-break particle effects
│   │   │   └── Renderer.java             # Master renderer (world, clouds, mobs, particles, HUD)
│   │   ├── mob/
│   │   │   ├── MobType.java              # ZOMBIE / SKELETON / CREEPER / SPIDER / PIG / SHEEP
│   │   │   ├── Mob.java                  # Position, type, and wandering AI state
│   │   │   └── MobManager.java           # Spawning and per-frame mob updates
│   │   └── input/
│   │       ├── InputAction.java          # Named action enum (JUMP, SPRINT, HOTBAR_1, …)
│   │       └── InputHandler.java         # GLFW keyboard/mouse/scroll state
│   └── resources/
│       ├── shaders/
│       │   ├── vertex.glsl               # World geometry vertex shader
│       │   ├── fragment.glsl             # World geometry fragment shader (alpha test + fog)
│       │   ├── hud_vertex.glsl           # HUD/icon vertex shader
│       │   ├── hud_fragment.glsl         # HUD fragment shader
│       │   ├── icon_vertex.glsl          # Icon/tinted quad vertex shader
│       │   ├── icon_fragment.glsl        # Icon/tinted quad fragment shader (uColor tint)
│       │   ├── cloud_vertex.glsl         # Cloud vertex shader
│       │   └── cloud_fragment.glsl       # Cloud fragment shader
│       └── textures/
│           ├── Blocks/                   # Per-block PNG tiles + block_textures.json
│           └── mob/                      # Per-mob PNG textures
└── test/
    └── java/com/blockgame/
        ├── BlockTypeTest.java
        ├── ChunkTest.java
        ├── FallingBlockBehaviorTest.java
        ├── SwimmingTest.java
        └── WorldTest.java
```

---

## System Documentation

Detailed documentation for each major game system lives in the `docs/` folder:

| Document | Contents |
|---|---|
| [World Generation](docs/systems/world-generation.md) | Terrain noise, biomes, caves, ores, trees |
| [Blocks](docs/systems/blocks.md) | Block types, properties, behaviors, and how to add new blocks |
| [Player](docs/systems/player.md) | Movement, physics, swimming, health, and block interaction |
| [Mobs](docs/systems/mobs.md) | Mob types, spawning, and wandering AI |
| [Rendering](docs/systems/rendering.md) | Render pipeline, texture atlas, clouds, particles, HUD |
| [Save & Load](docs/systems/save-load.md) | Save file format, versioning, and the Saveable interface |

---

## Extending the Game

* **Add a new block type** — add an entry to the `BlockType` enum with an id, RGB colour, `solid` / `transparent` flags, and an optional `BlockBehavior`. Then add the block's texture tiles to `src/main/resources/textures/Blocks/` and register them in `block_textures.json`. See [Blocks](docs/systems/blocks.md) for full details.
* **Add a world feature** — implement `WorldFeature` and register it in `World`'s constructor feature list. Features run in order: `CaveFeature` → `OreFeature` → `TreeFeature`.
* **Add a new biome** — add an entry to `BiomeType` and update `DefaultBiomeProvider.getBiome()` with the height thresholds, then handle the new biome in `DefaultWorldGenerator` and any relevant `WorldFeature`.
* **Change terrain shape** — edit `World.getTerrainHeight()`. Adjust the noise frequency, octave count, or height range for different landscapes.
* **Increase render distance** — change `World.RENDER_DISTANCE`.
* **Add a game system** — implement `GameSystem` and register it in `Game.init()`. The game loop calls `update(dt)` on every registered system each frame.
