package com.yagah.visarena.mode;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * What players see of the payload: a vanilla block display (so any client draws it, with no hitbox, health or physics for
 * an ability to catch), configured here and never saved with the world (a crash leaves no stray cart behind). It glides
 * between the positions the server sets each tick ("teleport_duration").
 */
final class PayloadCart extends Display.BlockDisplay {
    /** Ticks the client takes to glide to each new position: a little over one update, so it never stops between them. */
    private static final int GLIDE_TICKS = 3;

    private PayloadCart(ServerLevel level) {
        super(EntityType.BLOCK_DISPLAY, level);
    }

    /**
     * A block "size" blocks across, its middle "lift" blocks above the entity's position, optionally glowing in "glow"
     * (RGB, or -1 for none) and drawn full-bright.
     */
    static PayloadCart spawn(ServerLevel level, Vec3 at, String block, float size, float lift, int glow) {
        PayloadCart cart = new PayloadCart(level);
        CompoundTag tag = new CompoundTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        tag.put("block_state", state);
        CompoundTag transform = new CompoundTag();
        transform.put("translation", floats(-size / 2, lift - size / 2, -size / 2));
        transform.put("left_rotation", floats(0, 0, 0, 1));
        transform.put("scale", floats(size, size, size));
        transform.put("right_rotation", floats(0, 0, 0, 1));
        tag.put("transformation", transform);
        tag.putInt("teleport_duration", GLIDE_TICKS);
        tag.putFloat("view_range", 4);
        CompoundTag bright = new CompoundTag();
        bright.putInt("sky", 15);
        bright.putInt("block", 15);
        tag.put("brightness", bright);
        if (glow >= 0) {
            tag.putInt("glow_color_override", glow);
        }
        cart.readAdditionalSaveData(tag);
        cart.setGlowingTag(glow >= 0);
        cart.moveTo(at.x, at.y, at.z, 0, 0);
        level.addFreshEntity(cart);
        return cart;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
