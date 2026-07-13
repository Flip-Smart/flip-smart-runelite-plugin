package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import java.util.ArrayList;
import java.util.List;

public final class ActionResolver {

    public ActionDecision resolve(ResolverInput in) {
        List<ActionDecision> candidates = new ArrayList<>();

        for (OfferRecord r : in.getCompletedAwaitingCollection()) {
            long ts = r.getEffectiveLastActivityAtMillis();
            int slot = slotOf(r);
            if (!r.isBuy()) {
                candidates.add(new ActionDecision(ActionKind.S5, ActionStep.COLLECT, r.getItemId(), slot, ts));
            } else if (r.getState() == OfferState.CANCELLED_PARTIAL) {
                candidates.add(new ActionDecision(ActionKind.S1, ActionStep.COLLECT, r.getItemId(), slot, ts));
            } else {
                candidates.add(new ActionDecision(ActionKind.S3, ActionStep.COLLECT, r.getItemId(), slot, ts));
            }
        }

        boolean slotFree = in.getFilledSlotCount() < in.getSlotLimit();
        for (CollectedItem c : in.getCollectedAwaitingList()) {
            if (!c.hasSellPrice()) {
                continue;
            }
            if (!slotFree) {
                break;
            }
            // A collected item that is meant to be sold takes priority over placing a
            // new buy: the free slot should list what we already own rather than be
            // consumed by a new buy that leaves the held item with nowhere to sell.
            // Applies to both preemptive (partial-cancel) and normally-completed collects.
            candidates.add(new ActionDecision(ActionKind.SELL_WAITING, ActionStep.LIST,
                c.getItemId(), -1, c.getDetectedAtMillis()));
        }

        for (OfferRecord r : in.getStaleOffers()) {
            long ts = r.getEffectiveLastActivityAtMillis();
            int slot = slotOf(r);
            if (!r.isBuy()) {
                candidates.add(new ActionDecision(ActionKind.S4, ActionStep.REPRICE, r.getItemId(), slot, ts));
            } else if (r.getFilledQuantity() > 0) {
                candidates.add(new ActionDecision(ActionKind.S1, ActionStep.CANCEL, r.getItemId(), slot, ts));
            } else {
                candidates.add(new ActionDecision(ActionKind.S6, ActionStep.CANCEL, r.getItemId(), slot, ts));
            }
        }

        // Suppress a new buy while a just-collected item is awaiting its sell price: the free
        // slot must be held for the pending sell, not spent on a fresh purchase that would
        // strand the item we already own. Bounded by a grace window upstream so a permanently
        // unresolved price can't wedge trading.
        if (in.getFilledSlotCount() < in.getSlotLimit() && in.hasSurfaceableBuy()
            && !in.isBlockBuyForPendingSell()) {
            candidates.add(new ActionDecision(ActionKind.S2, ActionStep.PLACE_BUY,
                in.getSurfaceableBuyItemId(), -1, in.getNowMillis()));
        }

        ActionDecision best = null;
        for (ActionDecision c : candidates) {
            if (best == null || beats(c, best)) {
                best = c;
            }
        }
        return best == null ? ActionDecision.IDLE : best;
    }

    private static int slotOf(OfferRecord r) {
        Integer slot = r.getSlot();
        return slot == null ? -1 : slot;
    }

    private static boolean beats(ActionDecision a, ActionDecision b) {
        int byTier = Integer.compare(a.getKind().ordinal(), b.getKind().ordinal());
        if (byTier != 0) {
            return byTier < 0;
        }
        int byTime = Long.compare(a.getDetectedAtMillis(), b.getDetectedAtMillis());
        if (byTime != 0) {
            return byTime < 0;
        }
        return Integer.compare(slotKey(a), slotKey(b)) < 0;
    }

    private static int slotKey(ActionDecision d) {
        return d.getSlot() < 0 ? Integer.MAX_VALUE : d.getSlot();
    }
}
