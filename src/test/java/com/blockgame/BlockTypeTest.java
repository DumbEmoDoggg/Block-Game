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
            // AIR, WATER, and plant blocks are intentionally non-solid.
            if (bt == BlockType.AIR || bt == BlockType.WATER || bt.isPlant()) {
                continue;
            }
            assertTrue(bt.solid, bt + " should be solid");
            // LEAVES and GLASS are solid for collision but transparent for rendering.
            if (bt != BlockType.LEAVES && bt != BlockType.GLASS) {
                assertFalse(bt.isTransparent(), bt + " should not be transparent");
            }
        }
    }

    @Test
    void waterIsNonSolidAndTransparent() {
        assertFalse(BlockType.WATER.solid, "WATER must not be solid (passable)");
        assertTrue(BlockType.WATER.transparent, "WATER must be transparent for rendering");
        assertTrue(BlockType.WATER.isTransparent(), "WATER.isTransparent() must return true");
    }

    @Test
    void leavesAreTransparent() {
        assertTrue(BlockType.LEAVES.solid,       "LEAVES must remain solid for collision");
        assertTrue(BlockType.LEAVES.transparent, "LEAVES must be transparent for rendering");
        assertTrue(BlockType.LEAVES.isTransparent(), "LEAVES.isTransparent() must return true");
    }

    @Test
    void glassIsSolidAndTransparent() {
        assertTrue(BlockType.GLASS.solid,       "GLASS must be solid for collision");
        assertTrue(BlockType.GLASS.transparent, "GLASS must be transparent for rendering");
        assertTrue(BlockType.GLASS.isTransparent(), "GLASS.isTransparent() must return true");
    }

    @Test
    void plantBlocksAreNonSolidAndTransparent() {
        BlockType[] plants = {
            BlockType.DANDELION, BlockType.POPPY,
            BlockType.BROWN_MUSHROOM, BlockType.RED_MUSHROOM
        };
        for (BlockType bt : plants) {
            assertFalse(bt.solid, bt + " must not be solid");
            assertTrue(bt.transparent, bt + " must be transparent");
            assertTrue(bt.isPlant(), bt + " must have isPlant() == true");
        }
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
