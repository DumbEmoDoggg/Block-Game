package com.blockgame.player;

import com.blockgame.world.BlockType;

/**
 * A 36-slot player inventory (9 hotbar slots + 27 main storage slots).
 *
 * <p>Slots 0–8 are the hotbar; slots 9–35 are the main inventory.
 * Items are stacked up to {@value #MAX_STACK} per slot.
 */
public class Inventory {

    public static final int SIZE         = 36;
    public static final int HOTBAR_SIZE  = 9;
    public static final int MAX_STACK    = 64;

    private final ItemStack[] slots = new ItemStack[SIZE];

    // -------------------------------------------------------------------------
    // Default population
    // -------------------------------------------------------------------------

    /**
     * Pre-fills the hotbar with one of each standard block type so the player
     * can place blocks immediately without breaking any first.
     */
    public void fillDefaultHotbar() {
        BlockType[] defaults = {
            BlockType.GRASS, BlockType.DIRT,   BlockType.STONE,
            BlockType.WOOD,  BlockType.LEAVES, BlockType.SAND,
            BlockType.SNOW,  BlockType.PLANKS, BlockType.GRAVEL
        };
        for (int i = 0; i < defaults.length && i < HOTBAR_SIZE; i++) {
            slots[i] = new ItemStack(defaults[i], MAX_STACK);
        }
    }

    // -------------------------------------------------------------------------
    // Item insertion
    // -------------------------------------------------------------------------

    /**
     * Adds one item of {@code type} to the inventory.  First tries to stack
     * onto an existing partial stack of the same type; if none is found, places
     * into the first empty slot.
     *
     * @return {@code true} if the item was added, {@code false} if the
     *         inventory is full.
     */
    public boolean addItem(BlockType type) {
        // 1. Try to top up an existing partial stack
        for (int i = 0; i < SIZE; i++) {
            if (slots[i] != null && slots[i].type == type && slots[i].count < MAX_STACK) {
                slots[i].count++;
                return true;
            }
        }
        // 2. Find first empty slot
        for (int i = 0; i < SIZE; i++) {
            if (slots[i] == null) {
                slots[i] = new ItemStack(type, 1);
                return true;
            }
        }
        return false; // inventory full
    }

    // -------------------------------------------------------------------------
    // Slot access
    // -------------------------------------------------------------------------

    /** Returns the {@link ItemStack} in the given slot, or {@code null} if empty. */
    public ItemStack getSlot(int index) {
        return slots[index];
    }

    /** Sets the given slot directly. Pass {@code null} to clear it. */
    public void setSlot(int index, ItemStack stack) {
        slots[index] = stack;
    }

    // -------------------------------------------------------------------------
    // Hotbar helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link BlockType} of the item in hotbar slot {@code slot},
     * or {@link BlockType#AIR} if the slot is empty.
     */
    public BlockType getHotbarBlock(int slot) {
        ItemStack s = slots[slot];
        return (s != null && s.count > 0) ? s.type : BlockType.AIR;
    }

    /**
     * Decrements the count of the item in hotbar slot {@code slot} by one and
     * clears the slot when the count reaches zero.
     *
     * @return {@code true} if an item was consumed, {@code false} if the slot
     *         was already empty.
     */
    public boolean consumeHotbar(int slot) {
        ItemStack s = slots[slot];
        if (s == null || s.count == 0) return false;
        s.count--;
        if (s.count == 0) slots[slot] = null;
        return true;
    }
}
