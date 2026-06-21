package com.flipsmart.recommend;

import java.util.Objects;

public final class ActionDecision {

    public static final ActionDecision IDLE =
        new ActionDecision(ActionKind.IDLE, ActionStep.NONE, -1, -1, 0L);

    private final ActionKind kind;
    private final ActionStep step;
    private final int itemId;
    private final int slot;
    private final long detectedAtMillis;

    public ActionDecision(ActionKind kind, ActionStep step, int itemId, int slot, long detectedAtMillis) {
        this.kind = kind;
        this.step = step;
        this.itemId = itemId;
        this.slot = slot;
        this.detectedAtMillis = detectedAtMillis;
    }

    public ActionKind getKind() { return kind; }
    public ActionStep getStep() { return step; }
    public int getItemId() { return itemId; }
    public int getSlot() { return slot; }
    public long getDetectedAtMillis() { return detectedAtMillis; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActionDecision)) return false;
        ActionDecision that = (ActionDecision) o;
        return itemId == that.itemId && slot == that.slot
            && kind == that.kind && step == that.step;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, step, itemId, slot);
    }

    @Override
    public String toString() {
        return "ActionDecision{" + kind + "/" + step + " item=" + itemId
            + " slot=" + slot + " detectedAt=" + detectedAtMillis + '}';
    }
}
