# Session-corpus fixtures (plugin-owned)

These fixtures replay a **session timeline** — GE slot observations interleaved with world hops
and relogs — through the real `OfferStore` and `OfflineSyncService`, and assert what the store
retained and what fills were reported downstream.

## How this differs from `golden_corpus/`

| | `golden_corpus/` | `session_corpus/` (here) |
|---|---|---|
| Ownership | **Shared with the backend**, kept byte-identical | Plugin-only |
| Event unit | Already-reduced fills | Raw slot observations + lifecycle events |
| Replayed through | `RoundTripLedger` | `OfferStore` + `OfflineSyncService` |
| Can express a hop / relog / offline gap | No | Yes |

Do **not** move cases between the two directories. The golden corpus is kept identical with the
backend's copies, so plugin-only lifecycle events cannot go there. Retention and fill-accounting
defects live in the session lifecycle, which is why they need their own corpus.

## Fixture schema

```jsonc
{
  "name": "kebab-case-name",          // test display name
  "description": "why this case exists",
  "rsn": "TestPlayer",                 // optional, defaults to TestPlayer
  "known_failure": true,               // optional; skips assertions, for a pinned defect
  "timeline": [ /* events, applied in order */ ],
  "expect": { /* all keys optional */ }
}
```

### Timeline events

`at_seconds` is relative to a wall-clock base captured when the fixture runs. It must be
relative: the services age records against `System.currentTimeMillis()`, so absolute
small values fall outside every retention window and get silently dropped.

- **`observe`** — one `GrandExchangeOfferChanged`. Fields: `slot`, `state` (a
  `GrandExchangeOfferState` name), `item_id`, `total`, `price`, `sold`, `spent`.
  `sold` and `spent` are **cumulative**, exactly as the client reports them.
- **`hop`** — persist, then preload against the given snapshot. The store is *not* cleared.
- **`relog`** — the same, but the in-memory store is cleared first. The running instance survives,
  so anything held outside the store (the fill watermarks, for one) is retained.
- **`restart`** — a process boundary. The store *and* the service are rebuilt from nothing, so the
  config blob is the only thing that crosses. Use this, not `relog`, to test whether state actually
  round-trips through persistence: a `relog` will happily pass on in-memory state that was never
  written to disk.

Both `hop` and `relog` take:
- `ge_readable: false` — `getGrandExchangeOffers()` returns `null` (snapshot not loaded yet)
- `live_slots: []` with `ge_readable: true` — a non-null array where every slot reads `EMPTY`

Those two are **different states** and must be tested separately: a world hop presents as
non-null-all-EMPTY at the `LOGGED_IN` tick, not as null.

### Expectations

- **`buy_basis`** — `{itemId: expectedAverageBuyPrice}` via `BuyPriceLookup`. Asserts the cost
  basis a sell screen would render.
- **`live_slot_items`** — `{slot: itemId}` still live in the store.
- **`reported_fills`** — every `OfferEvent` carrying a fill, in order, as
  `{item_id, is_buy, quantity}`. **This is the over/under-count assertion.**

## Writing a fixture: three traps

1. **A collect only terminalizes a filled offer.** `BUYING → EMPTY` is silently rejected and the
   record stays `NEW`. Write `BUYING → BOUGHT → EMPTY`.
2. **Anchor times to the wall clock** via `at_seconds` (see above).
3. **`sold`/`spent` are cumulative, not per-event increments.** A partial fill of 4 then 3 more
   is `sold: 4` followed by `sold: 7`.
