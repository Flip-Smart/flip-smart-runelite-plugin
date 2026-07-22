# Golden-corpus fixtures (shared with the backend) — #1052

These JSON fixtures are the **shared source of truth** for the fill-accounting
regression corpus. The canonical copies live in the backend repo at
`tests/golden_corpus/fixtures/`; the files here are kept identical.

`GoldenCorpusTest` (in `src/test/java/com/flipsmart/trading/`) replays each
fixture's `events` through `RoundTripLedger` and asserts the
`plugin.round_trip_ids` segmentation — the plugin-side guard against the
same-item over-count class (#647 → #830 → #974) that the backend harness guards
from the flip-construction side.

## Adding / updating a case (one file)

1. Add or edit the JSON in the backend repo's `tests/golden_corpus/fixtures/`.
2. Copy the identical file here.
3. Ensure it carries a `plugin` block — either `round_trip_ids` (a list parallel
   to `events` of the ledger cycle id each fill should return) or
   `skip` with a reason when the case turns on a backend-only mechanism.

See the backend `tests/golden_corpus/README.md` for the full fixture schema.
