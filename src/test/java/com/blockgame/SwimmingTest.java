package com.blockgame;

import com.blockgame.world.BlockType;
import com.blockgame.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the world-level conditions that the Player's swimming mechanics
 * depend on:
 * <ul>
 *   <li>Water blocks are detectable at a given world coordinate.</li>
 *   <li>Water blocks are non-solid (the player AABB can overlap them).</li>
 *   <li>Air blocks are correctly distinguished from water blocks, so the
 *       player does <em>not</em> enter swimming mode in air.</li>
 * </ul>
 */
class SwimmingTest {

    @Test
    void waterBlockIsNonSolid() {
        assertFalse(BlockType.WATER.solid,
            "WATER must be non-solid so the player can enter it and swim");
    }

    @Test
    void worldDetectsWaterBlockAtSetPosition() {
        World world = new World();
        int wx = 5, wy = 110, wz = 5;

        world.setBlock(wx, wy, wz, BlockType.WATER);

        assertEquals(BlockType.WATER, world.getBlock(wx, wy, wz),
            "World should return WATER at the position it was placed");
    }

    @Test
    void worldDoesNotReportWaterInAir() {
        World world = new World();
        int wx = 5, wy = 110, wz = 5;

        // Ensure the block at this position is air (well above terrain)
        world.setBlock(wx, wy, wz, BlockType.AIR);

        assertNotEquals(BlockType.WATER, world.getBlock(wx, wy, wz),
            "An air block must not be detected as water");
    }

    @Test
    void waterBlockReplacedByAirNoLongerCountsAsWater() {
        World world = new World();
        int wx = 3, wy = 110, wz = 3;

        world.setBlock(wx, wy, wz, BlockType.WATER);
        assertEquals(BlockType.WATER, world.getBlock(wx, wy, wz));

        world.setBlock(wx, wy, wz, BlockType.AIR);
        assertNotEquals(BlockType.WATER, world.getBlock(wx, wy, wz),
            "After replacing water with air the block must no longer read as water");
    }
}
