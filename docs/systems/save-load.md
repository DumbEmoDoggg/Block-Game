# Save & Load

This document describes how game state is persisted to disk.

---

## Save File Location

The save file is written to:

```
~/.blockgame/world.dat
```

The directory is created automatically on first save. The file is a binary format — **not human-readable**.

---

## Triggering a Save / Load

| Action | Effect |
|---|---|
| Press **Enter** in-game | Saves the world and player position |
| Game startup | Loads the save file if it exists; starts a fresh world otherwise |

---

## Saveable Interface

Any component that needs to persist state implements `Saveable`:

```java
public interface Saveable {
    void save(DataOutputStream out) throws IOException;
    void load(DataInputStream in)  throws IOException;
}
```

Currently registered components:

| Key | Class | What is saved |
|---|---|---|
| `"world"` | `World` | All loaded chunks (block data for each) |
| `"player"` | `Player` | Position (x, y, z), yaw, pitch |

---

## File Format

The save file uses a **versioned tagged-section** layout:

```
int     SAVE_FORMAT_VERSION          (current: 2)
section* :
    UTF   key                        (e.g. "world", "player")
    int   dataLength                 (byte count of the section payload)
    byte[dataLength]  payload        (component-specific binary data)
UTF     ""                           (empty-string end marker)
```

`SAVE_FORMAT_VERSION` is checked on load. If the version does not match the current constant the load is aborted and a fresh world is generated instead. This prevents crashes from format changes between versions.

### World Section Payload

```
int   chunkCount
per chunk:
    int   chunkX
    int   chunkZ
    byte[16 × 128 × 16]  block ids   (one byte per block, YXZ order)
```

### Player Section Payload

```
float  x
float  y
float  z
float  yaw
float  pitch
```

---

## Extending the Save System

To persist state in a new component:

1. Implement `Saveable` in the component class.
2. Register it in `Game.saveGame()` and `Game.loadGame()` by adding it to the `handlers` map with a unique string key:
   ```java
   handlers.put("my_component", myComponent);
   ```
3. Implement `save()` and `load()` using the provided `DataOutputStream` / `DataInputStream`.

**Important:** increment `SAVE_FORMAT_VERSION` in `Game.java` whenever the on-disk layout changes in a backward-incompatible way (e.g. adding a required field to an existing section). The load path will then skip stale save files gracefully instead of throwing exceptions.
