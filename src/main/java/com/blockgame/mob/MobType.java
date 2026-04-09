package com.blockgame.mob;

/**
 * Identifies the kind of mob, governing its texture, model shape, and behaviour.
 *
 * <p>Each constant is self-describing: it carries the physical height used by
 * the physics simulation, the classpath paths to its skin textures, and a
 * {@link MobModel} instance that knows how to build the rendered geometry.
 * Adding a new mob type only requires a new constant here plus a corresponding
 * {@link MobModel} implementation – no changes to {@code MobRenderer} or
 * {@code Mob} are necessary.
 *
 * <p>The mobs correspond to those present in Java Edition Classic as documented
 * at <a href="https://minecraft.wiki/w/Java_Edition_Classic">the Minecraft wiki</a>.
 */
public enum MobType {

    /** Undead humanoid – hostile. Texture: zombie.png (64×64). */
    ZOMBIE(2.0f, "/textures/Mobs/zombie.png", null,
           new MobModel.ZombieModel()),

    /** Undead archer – hostile. Texture: skeleton.png (64×32). */
    SKELETON(2.0f, "/textures/Mobs/skeleton.png", null,
             new MobModel.SkeletonModel()),

    /** Four-legged explosive – hostile. Texture: creeper.png (64×32). */
    CREEPER(1.7f, "/textures/Mobs/creeper.png", null,
            new MobModel.CreeperModel()),

    /** Eight-legged arachnid – hostile. Textures: spider.png + spider_eyes.png (64×32). */
    SPIDER(0.7f, "/textures/Mobs/spider.png", "/textures/Mobs/spider_eyes.png",
           new MobModel.SpiderModel()),

    /** Pink passive quadruped. Texture: pig_temperate.png (64×64). */
    PIG(1.2f, "/textures/Mobs/pig_temperate.png", null,
        new MobModel.PigModel()),

    /** Woolly passive quadruped. Textures: sheep.png + sheep_wool.png (64×32). */
    SHEEP(1.2f, "/textures/Mobs/sheep.png", "/textures/Mobs/sheep_wool.png",
          new MobModel.SheepModel());

    // -------------------------------------------------------------------------

    /** Physical height in block-units from feet to top of head. */
    public final float height;

    /** Classpath path to the primary skin texture (never {@code null}). */
    public final String primaryTexture;

    /**
     * Classpath path to the translucent overlay texture, or {@code null} if
     * this mob type has no overlay pass (e.g. spider eyes, sheep wool).
     */
    public final String overlayTexture;

    /** Geometry model used by the renderer to build this mob's mesh. */
    public final MobModel model;

    MobType(float height, String primaryTexture, String overlayTexture, MobModel model) {
        this.height         = height;
        this.primaryTexture = primaryTexture;
        this.overlayTexture = overlayTexture;
        this.model          = model;
    }

    /** Returns {@code true} if this mob type has a translucent overlay render pass. */
    public boolean hasOverlay() {
        return overlayTexture != null;
    }
}
