# Block Game

A basic Minecraft-inspired block-building game written in **Java 17** using [LWJGL 3](https://www.lwjgl.org/) for OpenGL rendering.

---

## Features

| Feature | Details |
|---|---|
| 3-D block world | 16 × 16 × 128 chunks, rendered with OpenGL 3.3 core profile |
| Procedural terrain | Multi-octave Perlin noise (fBm) — smooth hills, valleys, and snow-capped peaks |
| Block types | Air, Grass, Dirt, Stone, Wood, Leaves, Sand, Snow |
| Block interaction | **Left-click** to remove a block · **Right-click** to place one |
| Movement | WASD to walk · Space to jump · Left-Ctrl to sprint |
| Mouse look | Smooth first-person camera |
| Hotbar | Keys **1–7** or **scroll wheel** to cycle block types (shown at the bottom of the screen) |
| Crosshair | Always-visible white crosshair |
| Face lighting | Top = brightest · north/south = medium · east/west = darker · bottom = darkest |
| Save / Load | Press **Enter** to save the current world and player position to `~/.blockgame/world.dat`; the save is loaded automatically on next startup |

---

## Controls

| Key / Button | Action |
|---|---|
| W A S D | Walk |
| Space | Jump |
| Left Ctrl | Sprint |
| Mouse | Look around |
| Left-click | Break block |
| Right-click | Place selected block |
| 1 – 7 | Select hotbar slot |
| Scroll wheel | Cycle hotbar slot |
| Enter | Save world & player position |
| Escape | Quit |

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

---

## Running

### Auto-updating launcher (recommended)

The easiest way to play.  Download **once**, and the launcher keeps your game up to date automatically — no need to re-download the artifact after every update.

1. Download `BlockGameLauncher.bat` and `BlockGameLauncher.ps1` from the [**latest-build** release](https://github.com/DumbEmoDoggg/Block-Game/releases/tag/latest-build) into the same folder.
2. Double-click `BlockGameLauncher.bat`.
   * On first run the game is downloaded automatically into a `BlockGame\` sub-folder.
   * On subsequent runs the launcher checks for a newer build; if one exists it is downloaded before the game starts.
3. That's it — the game launches automatically.

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
│   │   ├── Main.java              # Entry point
│   │   ├── Game.java              # Game loop & GLFW window
│   │   ├── world/
│   │   │   ├── BlockType.java     # Block type enum (add new blocks here)
│   │   │   ├── Chunk.java         # 16×16×128 chunk data
│   │   │   ├── PerlinNoise.java   # Multi-octave Perlin noise (fBm terrain)
│   │   │   └── World.java         # Infinite chunk grid + terrain generation + save/load
│   │   ├── player/
│   │   │   ├── Camera.java        # View/projection matrices
│   │   │   └── Player.java        # Movement, physics, block interaction
│   │   ├── rendering/
│   │   │   ├── Shader.java        # GLSL shader loader/compiler
│   │   │   ├── ChunkMesh.java     # Per-chunk VAO/VBO mesh builder
│   │   │   └── Renderer.java      # Frame renderer (world + HUD)
│   │   └── input/
│   │       └── InputHandler.java  # GLFW keyboard/mouse state
│   └── resources/shaders/
│       ├── vertex.glsl
│       ├── fragment.glsl
│       ├── hud_vertex.glsl
│       └── hud_fragment.glsl
└── test/
    └── java/com/blockgame/
        ├── BlockTypeTest.java
        ├── ChunkTest.java
        └── WorldTest.java
```

---

## Extending the Game

* **Add a new block type** — add an entry to the `BlockType` enum with an id, RGB colour, and `solid` flag. No other changes needed.
* **Change terrain** — edit `World.getTerrainHeight()`. Adjust the noise frequency, octave count, or height range for different landscapes.
* **Increase render distance** — change `World.RENDER_DISTANCE`.
* **Textures** — replace the per-vertex colour with UV coordinates and add a texture atlas in `ChunkMesh`.
