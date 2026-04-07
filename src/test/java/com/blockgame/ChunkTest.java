package com.blockgame;

import com.blockgame.world.BlockType;
import com.blockgame.world.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkTest {

    private Chunk chunk;

    @BeforeEach
    void setUp() {
        chunk = new Chunk(0, 0);
    }

    @Test
    void newChunkIsDirty() {
        assertTrue(chunk.isDirty(), "A freshly created chunk should be dirty");
    }

    @Test
    void setAndGetBlock() {
        chunk.setBlock(3, 10, 7, BlockType.STONE);
        assertEquals(BlockType.STONE, chunk.getBlock(3, 10, 7));
    }

    @Test
    void defaultBlockIsAir() {
        assertEquals(BlockType.AIR, chunk.getBlock(0, 0, 0));
        assertEquals(BlockType.AIR, chunk.getBlock(5, 50, 5));
    }

    @Test
    void outOfBoundsReturnsAir() {
        assertEquals(BlockType.AIR, chunk.getBlock(-1, 0, 0));
        assertEquals(BlockType.AIR, chunk.getBlock(0, -1, 0));
        assertEquals(BlockType.AIR, chunk.getBlock(0, 0, -1));
        assertEquals(BlockType.AIR, chunk.getBlock(Chunk.SIZE, 0, 0));
        assertEquals(BlockType.AIR, chunk.getBlock(0, Chunk.HEIGHT, 0));
        assertEquals(BlockType.AIR, chunk.getBlock(0, 0, Chunk.SIZE));
    }

    @Test
    void outOfBoundsSetIsIgnored() {
        // Should not throw
        assertDoesNotThrow(() -> chunk.setBlock(-1, 0, 0, BlockType.STONE));
        assertDoesNotThrow(() -> chunk.setBlock(Chunk.SIZE, 0, 0, BlockType.STONE));
    }

    @Test
    void setBlockMarksDirty() {
        chunk.setDirty(false);
        assertFalse(chunk.isDirty());

        chunk.setBlock(1, 1, 1, BlockType.GRASS);
        assertTrue(chunk.isDirty(), "setBlock should mark the chunk dirty");
    }

    @Test
    void worldCoordinateConversion() {
        Chunk c = new Chunk(2, -3);
        assertEquals(2 * Chunk.SIZE + 5, c.getWorldX(5));
        assertEquals(-3 * Chunk.SIZE + 8, c.getWorldZ(8));
    }

    @Test
    void setDirtyFlag() {
        chunk.setDirty(false);
        assertFalse(chunk.isDirty());
        chunk.setDirty(true);
        assertTrue(chunk.isDirty());
    }

    @Test
    void overwriteBlock() {
        chunk.setBlock(0, 0, 0, BlockType.GRASS);
        chunk.setBlock(0, 0, 0, BlockType.DIRT);
        assertEquals(BlockType.DIRT, chunk.getBlock(0, 0, 0));
    }

    @Test
    void chunkSizeConstants() {
        assertTrue(Chunk.SIZE   > 0,  "Chunk.SIZE must be positive");
        assertTrue(Chunk.HEIGHT > 0,  "Chunk.HEIGHT must be positive");
    }
}
