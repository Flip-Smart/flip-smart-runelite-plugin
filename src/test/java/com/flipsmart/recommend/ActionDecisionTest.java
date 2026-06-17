package com.flipsmart.recommend;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ActionDecisionTest {

    @Test
    public void idleConstantHasIdleKindAndNoItem() {
        assertEquals(ActionKind.IDLE, ActionDecision.IDLE.getKind());
        assertEquals(ActionStep.NONE, ActionDecision.IDLE.getStep());
        assertEquals(-1, ActionDecision.IDLE.getItemId());
        assertEquals(-1, ActionDecision.IDLE.getSlot());
    }

    @Test
    public void equalityIgnoresDetectedAtMillis() {
        ActionDecision a = new ActionDecision(ActionKind.S1, ActionStep.LIST, 555, 3, 1000L);
        ActionDecision b = new ActionDecision(ActionKind.S1, ActionStep.LIST, 555, 3, 9999L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void differentKindOrStepOrItemOrSlotAreNotEqual() {
        ActionDecision base = new ActionDecision(ActionKind.S3, ActionStep.COLLECT, 1, 0, 0L);
        assertNotEquals(base, new ActionDecision(ActionKind.S5, ActionStep.COLLECT, 1, 0, 0L));
        assertNotEquals(base, new ActionDecision(ActionKind.S3, ActionStep.LIST, 1, 0, 0L));
        assertNotEquals(base, new ActionDecision(ActionKind.S3, ActionStep.COLLECT, 2, 0, 0L));
        assertNotEquals(base, new ActionDecision(ActionKind.S3, ActionStep.COLLECT, 1, 7, 0L));
    }

    @Test
    public void priorityFollowsDeclarationOrder() {
        assertTrue(ActionKind.S1.ordinal() < ActionKind.S2.ordinal());
        assertTrue(ActionKind.S5.ordinal() < ActionKind.S6.ordinal());
        assertTrue(ActionKind.S6.ordinal() < ActionKind.IDLE.ordinal());
    }
}
