package dev.streamable.ui.kit;

/** A value that eases toward a target; used for hover, press and expand transitions. */
public final class Anim {

    private float value;
    private float target;
    private final float duration;

    public Anim(float duration) {
        this.duration = Math.max(0.001f, duration);
    }

    public float update(float target, float delta) {
        this.target = target;
        float step = delta / duration;
        if (value < target) {
            value = Math.min(target, value + step);
        } else if (value > target) {
            value = Math.max(target, value - step);
        }
        return eased();
    }

    public float value() {
        return value;
    }

    /** Smoothstep of the linear value, for natural-looking motion. */
    public float eased() {
        return value * value * (3 - 2 * value);
    }

    public void snap(float v) {
        value = v;
        target = v;
    }
}
