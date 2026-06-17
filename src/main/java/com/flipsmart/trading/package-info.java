/**
 * Offer-state core: the single source of truth for Grand Exchange offer tracking.
 *
 * <h2>State model</h2>
 * <pre>
 *   NEW ──fill──► PARTIAL_FILL ──fill──► FILLED ──collect──► COLLECTED (terminal)
 *    │                  │
 *    └──cancel──► CANCELLED_EMPTY (terminal)
 *                       └──cancel──► CANCELLED_PARTIAL ──collect──► COLLECTED (terminal)
 * </pre>
 * <ul>
 *   <li>{@code NEW} — offer placed, no fills received yet.</li>
 *   <li>{@code PARTIAL_FILL} — one or more fills received; order still open.</li>
 *   <li>{@code FILLED} — fully matched; awaiting player collection from the GE interface.</li>
 *   <li>{@code CANCELLED_EMPTY} — cancelled before any fill; terminal with no collectable items.</li>
 *   <li>{@code CANCELLED_PARTIAL} — cancelled after partial fill; awaiting collection of partial items.</li>
 *   <li>{@code COLLECTED} — player collected coins/items; terminal; slot is freed.</li>
 * </ul>
 *
 * <h2>Single-owner invariant</h2>
 * {@link com.flipsmart.trading.OfferStore} is the sole writer of offer state. All other
 * components receive immutable {@link com.flipsmart.domain.offer.OfferRecord} snapshots and
 * must not attempt to mutate them. Every state transition is decided exclusively by
 * {@link com.flipsmart.trading.OfferStateMachine#decide decide()} — illegal or redundant
 * transitions are rejected (logged as WARN) and the current record is returned unchanged;
 * they are never silently applied.
 *
 * <h2>Keying</h2>
 * The primary key is a synthetic monotonic {@code offerId} minted at NEW. It survives slot
 * reuse (a slot cleared and re-used by a new offer gets a fresh id) and persists through
 * collection, making it the stable persistence and idempotency key.
 * {@code slotToOfferId} (slot indices 0–7) resolves incoming RuneLite GE events to the
 * current live offer for that slot. The store also maintains an item-id index so consumers
 * can ask "what is happening with item X?" via
 * {@link com.flipsmart.trading.OfferStore#forItem forItem()}.
 *
 * <h2>Threading contract</h2>
 * {@code OfferStore} is the single synchronization point. The compound sequence of
 * resolve → decide → update → snapshot runs under the store's intrinsic monitor. Listeners
 * registered via {@link com.flipsmart.trading.OfferStore#addListener addListener()} are
 * notified after the monitor is released, so a slow listener cannot block concurrent
 * readers. All reads return immutable snapshots. UI work derived from
 * {@link com.flipsmart.trading.OfferEvent} notifications must be marshalled to the EDT.
 *
 * <h2>Persistence and reconciliation</h2>
 * Offer records are persisted keyed by {@code offerId}. On login or reload,
 * {@link com.flipsmart.trading.OfferReconciler#reconcile reconcile()} compares persisted
 * records against the live GE slots reported by RuneLite: matching records are reattached
 * (slot index restored), unmatched live slots are minted as new offers with a baseline fill,
 * and persisted records not present in any live slot are marked as offline-collected.
 */
package com.flipsmart.trading;
