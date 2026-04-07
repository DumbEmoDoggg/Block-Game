package com.blockgame;

import com.blockgame.world.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockTypeTest {

    @Test
    void airIsNotSolid() {
        assertFalse(BlockType.AIR.solid, "AIR must not be solid");
    }

    @Test
    void airIsTransparent() {
        assertTrue(BlockType.AIR.isTransparent(), "AIR must be transparent");
    }

    @Test
    void solidBlocksAreSolid() {
        for (BlockType bt : BlockType.values()) {
            if (bt != BlockType.AIR) {
                assertTrue(bt.solid, bt + " should be solid");
                // LEAVES is solid for collision but transparent for rendering (see-through gaps)
                if (bt != BlockType.LEAVES) {
                    assertFalse(bt.isTransparent(), bt + " should not be transparent");
                }
            }
        }
    }

    @Test
    void leavesAreTransparent() {
        assertTrue(BlockType.LEAVES.solid,       "LEAVES must remain solid for collision");
        assertTrue(BlockType.LEAVES.transparent, "LEAVES must be transparent for rendering");
        assertTrue(BlockType.LEAVES.isTransparent(), "LEAVES.isTransparent() must return true");
    }

    @Test
    void fromIdRoundTrip() {
        for (BlockType bt : BlockType.values()) {
            assertEquals(bt, BlockType.fromId(bt.id),
                "fromId(id) should return the same BlockType for " + bt);
        }
    }

    @Test
    void fromIdUnknownReturnsAir() {
        assertEquals(BlockType.AIR, BlockType.fromId(255),
            "Unknown id should return AIR");
    }

    @Test
    void idsAreUnique() {
        long distinct = java.util.Arrays.stream(BlockType.values())
            .mapToInt(bt -> bt.id)
            .distinct()
            .count();
        assertEquals(BlockType.values().length, distinct, "All BlockType ids must be unique");
    }

    @Test
    void colorComponentsInRange() {
        for (BlockType bt : BlockType.values()) {
            assertTrue(bt.r >= 0f && bt.r <= 1f, bt + " r out of range");
            assertTrue(bt.g >= 0f && bt.g <= 1f, bt + " g out of range");
            assertTrue(bt.b >= 0f && bt.b <= 1f, bt + " b out of range");
        }
    }
}
