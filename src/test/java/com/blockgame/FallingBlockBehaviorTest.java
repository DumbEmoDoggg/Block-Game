package com.blockgame;

import com.blockgame.world.BlockType;
import com.blockgame.world.FallingBlockBehavior;
import com.blockgame.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallingBlockBehaviorTest {

    @Test
    void sandAndGravelHaveFallingBehavior() {
        assertNotNull(BlockType.SAND.behavior,   "SAND should have a FallingBlockBehavior");
        assertNotNull(BlockType.GRAVEL.behavior, "GRAVEL should have a FallingBlockBehavior");
        assertInstanceOf(FallingBlockBehavior.class, BlockType.SAND.behavior,
            "SAND behavior should be FallingBlockBehavior");
        assertInstanceOf(FallingBlockBehavior.class, BlockType.GRAVEL.behavior,
            "GRAVEL behavior should be FallingBlockBehavior");
    }

    @Test
    void sandFallsIntoAirBelow() {
        World world = new World();
        // Place sand above an air gap at a height above the terrain
        int wx = 0, wy = 110, wz = 0;
        world.setBlock(wx, wy,     wz, BlockType.AIR);   // ensure below is air
        world.setBlock(wx, wy + 1, wz, BlockType.SAND);  // place sand one above

        // One tick should move sand down by one
        world.update(new Vector3f(wx, wy, wz));

        assertEquals(BlockType.SAND, world.getBlock(wx, wy, wz),
            "Sand should have fallen one block down");
        assertEquals(BlockType.AIR, world.getBlock(wx, wy + 1, wz),
            "Original sand position should now be air");
    }

    @Test
    void gravelFallsIntoAirBelow() {
        World world = new World();
        int wx = 0, wy = 110, wz = 0;
        world.setBlock(wx, wy,     wz, BlockType.AIR);
        world.setBlock(wx, wy + 1, wz, BlockType.GRAVEL);

        world.update(new Vector3f(wx, wy, wz));

        assertEquals(BlockType.GRAVEL, world.getBlock(wx, wy, wz),
            "Gravel should have fallen one block down");
        assertEquals(BlockType.AIR, world.getBlock(wx, wy + 1, wz),
            "Original gravel position should now be air");
    }

    @Test
    void sandDoesNotFallOntoSolid() {
        World world = new World();
        int wx = 0, wy = 110, wz = 0;
        world.setBlock(wx, wy,     wz, BlockType.STONE);  // solid landing surface
        world.setBlock(wx, wy + 1, wz, BlockType.SAND);   // sand resting on stone

        world.update(new Vector3f(wx, wy, wz));

        assertEquals(BlockType.SAND, world.getBlock(wx, wy + 1, wz),
            "Sand should stay in place when supported by a solid block");
        assertEquals(BlockType.STONE, world.getBlock(wx, wy, wz),
            "Stone below should be unchanged");
    }

    @Test
    void sandFallsThroughWater() {
        World world = new World();
        int wx = 0, wy = 110, wz = 0;
        world.setBlock(wx, wy,     wz, BlockType.WATER);  // water is non-solid
        world.setBlock(wx, wy + 1, wz, BlockType.SAND);

        world.update(new Vector3f(wx, wy, wz));

        assertEquals(BlockType.SAND, world.getBlock(wx, wy, wz),
            "Sand should displace water and fall into it");
    }

    @Test
    void sandFallsMultipleBlocksOverSeveralTicks() {
        World world = new World();
        int wx = 0, wz = 0;
        // Clear three blocks and place sand at the top of the gap
        int top = 112;
        world.setBlock(wx, top - 2, wz, BlockType.STONE); // landing
        world.setBlock(wx, top - 1, wz, BlockType.AIR);
        world.setBlock(wx, top,     wz, BlockType.AIR);
        world.setBlock(wx, top + 1, wz, BlockType.SAND);   // sand starts here

        Vector3f pos = new Vector3f(wx, top, wz);

        // Each tick drops sand by one; after two ticks it should rest on stone
        world.update(pos); // falls to top
        world.update(pos); // falls to top-1
        world.update(pos); // lands on stone at top-2, stays at top-1

        assertEquals(BlockType.SAND, world.getBlock(wx, top - 1, wz),
            "Sand should have fallen to rest on the stone");
        assertEquals(BlockType.AIR, world.getBlock(wx, top + 1, wz),
            "Original sand position should be air");
    }
}
