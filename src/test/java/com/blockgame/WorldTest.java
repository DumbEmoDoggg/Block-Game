package com.blockgame;

import com.blockgame.world.BlockType;
import com.blockgame.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldTest {

    @Test
    void initialChunksAreGenerated() {
        World world = new World();
        // At minimum, the chunk containing the origin should be loaded
        assertNotNull(world.getChunk(0, 0), "Chunk (0,0) should be loaded after construction");
    }

    @Test
    void getBlockInUnloadedChunkReturnsAir() {
        World world = new World();
        // A very distant chunk that hasn't been loaded
        assertEquals(BlockType.AIR, world.getBlock(100_000, 64, 100_000));
    }

    @Test
    void setAndGetBlock() {
        World world = new World();
        world.setBlock(0, 70, 0, BlockType.STONE);
        assertEquals(BlockType.STONE, world.getBlock(0, 70, 0));
    }

    @Test
    void setBlockBelowZeroIgnored() {
        World world = new World();
        // Should not throw and block should remain AIR
        assertDoesNotThrow(() -> world.setBlock(0, -1, 0, BlockType.STONE));
        assertEquals(BlockType.AIR, world.getBlock(0, -1, 0));
    }

    @Test
    void terrainHeightIsPositive() {
        World world = new World();
        for (int x = -32; x <= 32; x += 8) {
            for (int z = -32; z <= 32; z += 8) {
                assertTrue(world.getTerrainHeight(x, z) > 0,
                    "Terrain height at (" + x + "," + z + ") must be positive");
            }
        }
    }

    @Test
    void updateLoadsNearbyChunks() {
        World world = new World();
        // Move player far away and call update
        world.update(new Vector3f(5 * 16, 64, 5 * 16));
        assertNotNull(world.getChunk(5, 5), "Chunk near player should be loaded after update");
    }

    @Test
    void chunksBecomesDirtyOnBorderSet() {
        World world = new World();
        // Force load adjacent chunks
        world.getOrLoadChunk(0, 0);
        world.getOrLoadChunk(-1, 0);

        world.getChunk(-1, 0).setDirty(false);
        // Setting a block on the western border of chunk (0,0) should dirty chunk (-1,0)
        world.setBlock(0, 64, 0, BlockType.STONE);   // lx=0 → border with cx=-1
        assertTrue(world.getChunk(-1, 0).isDirty(),
            "Adjacent chunk should be dirtied when border block is set");
    }
}
