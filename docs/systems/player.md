# Player

This document covers the player controller: movement, physics, swimming, health, and block interaction.

---

## Movement

The player moves on the XZ plane in the direction the camera is facing (yaw only — vertical look does not affect walking direction).

| Constant | Value | Description |
|---|---|---|
| `MOVE_SPEED` | 5.0 m/s | Normal walking speed |
| `SPRINT_SPEED` | 8.5 m/s | Speed while Left-Ctrl is held |
| `JUMP_VELOCITY` | 8.5 m/s | Upward velocity applied on jump |
| `GRAVITY` | −22.0 m/s² | Downward acceleration on land |

Sprint is engaged by holding Left-Ctrl while moving forward.

---

## Collision

The player is represented by an axis-aligned bounding box (AABB):

| Dimension | Value |
|---|---|
| Width / depth | 0.6 blocks |
| Height | 1.8 blocks |
| Eye height | 1.62 blocks (above feet) |

Collision is resolved by sweeping the AABB against solid world blocks each frame. The player cannot walk through any block with `solid = true`.

---

## Swimming

When any part of the player's AABB overlaps a `WATER` block the player is considered **in water** and different physics apply:

| Constant | Value | Description |
|---|---|---|
| `SWIM_SPEED` | 70 % of `MOVE_SPEED` | Horizontal speed in water |
| `SPRINT_SWIM_SPEED` | 70 % of `SPRINT_SPEED` | Sprint speed in water |
| `WATER_GRAVITY` | −4.0 m/s² | Gentle downward pull while submerged |
| `WATER_SINK_MAX` | −2.0 m/s | Terminal sinking velocity |
| `SWIM_UP_SPEED` | 4.0 m/s | Upward velocity while Jump is held |

Hold **Space** to swim upward; release to sink slowly. The player can sprint-swim in any horizontal direction.

When the player's eyes (at eye height above the feet) are submerged, the renderer applies an underwater tint and fog overlay.

---

## Health

The player has `MAX_HEALTH = 20` HP (10 hearts). The health bar is displayed on the HUD above the left side of the hotbar.

| Method | Effect |
|---|---|
| `damage(int amount)` | Reduces health; clamped to 0 |
| `heal(int amount)` | Increases health; clamped to MAX_HEALTH |
| `getHealth()` | Returns current HP |
| `getMaxHealth()` | Returns 20 |

There is currently no damage source in the game; the API exists for future combat and environmental hazards.

---

## Block Interaction

The player can interact with blocks within a **5-block reach** using a ray-cast from the camera.

| Action | Input | Effect |
|---|---|---|
| Break block | Left-click (hold) | Removes the targeted block; spawns break particles |
| Place block | Right-click (hold) | Places the selected hotbar block on the targeted face |

A **200 ms cooldown** is enforced between block actions to prevent accidental rapid removal.

Only blocks with `breakable = true` can be removed (Bedrock cannot be broken).

The targeted block is highlighted with a white wireframe overlay while aimed at.

---

## Hotbar

Eight block types are available on the hotbar:

| Slot | Block |
|---|---|
| 1 | Grass |
| 2 | Dirt |
| 3 | Stone |
| 4 | Wood |
| 5 | Leaves |
| 6 | Sand |
| 7 | Snow |
| 8 | Planks |

Select slots with keys **1–8** or scroll the mouse wheel to cycle.

---

## Camera

`Camera` computes the view and projection matrices from the player's yaw, pitch, and eye position.

* Mouse movement updates yaw (left/right) and pitch (up/down), clamped to ±90°.
* Mouse sensitivity is `0.12` degrees per pixel.
* Press **Escape** to toggle between captured (first-person look) and free cursor mode.

---

## Save & Load

Player state serialized to the save file includes position (x, y, z), yaw, and pitch. See [Save & Load](save-load.md) for the full format.
