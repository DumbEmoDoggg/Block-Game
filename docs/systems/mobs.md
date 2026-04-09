# Mobs

This document covers mob types, spawning, and wandering AI.

---

## Mob Types

Six mob types are defined in the `MobType` enum, based on Java Edition Classic mobs:

| Type | Category | Texture(s) | Size |
|---|---|---|---|
| `ZOMBIE` | Hostile | `zombie.png` (64×64) | Humanoid |
| `SKELETON` | Hostile | `skeleton.png` (64×32) | Humanoid |
| `CREEPER` | Hostile | `creeper.png` (64×32) | Humanoid |
| `SPIDER` | Hostile | `spider.png` + `spider_eyes.png` (64×32) | Low / wide |
| `PIG` | Passive | `pig_temperate.png` (64×64) | Quadruped |
| `SHEEP` | Passive | `sheep.png` + `sheep_wool.png` (64×32) | Quadruped |

Mob textures are stored in `src/main/resources/textures/mob/`.

---

## Spawning

`MobManager` spawns **12 mobs** at game startup (2 of each type) in a ring around the player's initial spawn position:

| Parameter | Value |
|---|---|
| Total mobs | 12 (2× each `MobType`) |
| Minimum spawn radius | 10 blocks |
| Maximum spawn radius | 24 blocks |
| Spawn height | Terrain surface + 1 |

Mobs are not currently re-spawned after they are unloaded or removed. All 12 are created once on the first call to `MobManager.spawnInitial(playerPos)`.

---

## Wandering AI

Each mob wanders randomly using a simple timer-based state machine:

1. A **wander target** is chosen within ±16 blocks of the mob's current position at terrain surface height.
2. The mob moves toward the target at a fixed speed.
3. After reaching the target (or after a timeout), a new target is chosen.

Mobs do not currently have pathfinding, combat, or player tracking. They walk in straight lines toward their current target regardless of obstacles.

### Speed

Mob move speed is set per-mob instance and is currently uniform for all types.

---

## Rendering

`MobRenderer` draws all mobs each frame using textured block-model geometry. Humanoid mobs (Zombie, Skeleton, Creeper) and animal mobs (Pig, Sheep) use different body-part layouts. Spider has an additional eye-layer texture drawn on top.

See [Rendering](rendering.md) for pipeline details.

---

## Adding a New Mob Type

1. Add an entry to `MobType`:
   ```java
   MY_MOB,
   ```
2. Place the mob's texture(s) in `src/main/resources/textures/mob/`.
3. Add UV coordinate arrays and a draw call for the new type in `MobRenderer`.
4. The new type will be included in spawning automatically (round-robin over all `MobType` values).
