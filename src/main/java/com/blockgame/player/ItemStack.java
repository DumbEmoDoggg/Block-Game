package com.blockgame.player;

import com.blockgame.world.BlockType;

/** A stack of identical block items. */
public class ItemStack {
    public BlockType type;
    public int count;

    public ItemStack(BlockType type, int count) {
        this.type  = type;
        this.count = count;
    }

    public ItemStack copy() {
        return new ItemStack(type, count);
    }
}
