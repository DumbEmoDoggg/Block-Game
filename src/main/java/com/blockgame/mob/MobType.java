package com.blockgame.mob;

/**
 * Identifies the kind of mob, governing its texture, model shape, and behaviour.
 *
 * <p>The mobs correspond to those present in Java Edition Classic as documented
 * at <a href="https://minecraft.wiki/w/Java_Edition_Classic">the Minecraft wiki</a>.
 */
public enum MobType {
    /** Undead humanoid – hostile. Texture: zombie.png (64×64). */
    ZOMBIE,
    /** Undead archer – hostile. Texture: skeleton.png (64×32). */
    SKELETON,
    /** Four-legged explosive – hostile. Texture: creeper.png (64×32). */
    CREEPER,
    /** Eight-legged arachnid – hostile. Textures: spider.png + spider_eyes.png (64×32). */
    SPIDER,
    /** Pink passive quadruped. Texture: pig_temperate.png (64×64). */
    PIG,
    /** Woolly passive quadruped. Textures: sheep.png + sheep_wool.png (64×32). */
    SHEEP
}
