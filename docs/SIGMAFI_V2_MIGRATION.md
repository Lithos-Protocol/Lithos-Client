# SigmaFi V2 integration update

The client pins Sigma-Finance-V2 at `6384141` (including the context-output mapping in `c8654db`).
The six contract sources and compiled prototypes match that repository's generated
`docs/LITHOS_SIGMAFI_V2_REFERENCE.md`. Contract regression tests compile the local copies and compare
their complete trees, hashes, addresses and constant positions with those exported values.

## Protocol and API changes

- Bond R6 is now four Longs: repayment, lender-funded reserve, agreed principal ERG, and maturity.
  R7 carries the agreed principal token list. Fixed maturity and on-close duration use Longs.
  Principal displays therefore need no separate order lookup. Mint authentication still verifies the order.
- The borrower locks its entire collateral in an order. The lender chooses and funds the reserve above
  that value on fill. The configured default remains **0.05 ERG**. Both fill endpoints accept an optional
  JSON body, for example `{"batchingFee":"75000000"}`. An omitted body uses the default. Zero is valid;
  fees below the automatic batcher floor produce a quote warning. `batchingFee` on order creation is
  rejected because an order cannot enforce that term anymore.
- Repayment returns the full borrower collateral, excluding the lender-funded reserve. The reserve is
  refunded in the lender's repayment vault. Quote costs exclude the reserve already held in the bond.
- Every settlement vault carries R4 = closing bond ID, R5 = asset ID and R6 Byte = 0 for repayment or 1
  for liquidation. Readers authenticate the actual indexed spend, context-selected output, tags and burn;
  arbitrary deposits or storage-rent spends do not establish settlement.
- Settlement context variable 1 is an Int output index. Repayment context variable 2 selects a distinct
  collateral return output. Builders no longer depend on matching input and output positions.

## Reads and liquidation batching

Asset lookup reads each verified bond directly from its ID instead of scanning every bond tree/template.
The same indexed read supplies both its live view and settlement state. Assets closed in the same batch
share one settlement-transaction read per request. A wallet repaying its own bond
resolves its borrower script locally before requesting origin history. Historical settlement evidence
remains available after the lender claims its vault.

Liquidation batches set an output index on each bond, add the liquidation marker and burn every
singleton. Failed combined builds split into smaller batches, respecting available candidate slots
and the build deadline; only failing individual bonds are temporarily skipped. Statistics follow the
same context indices. Automatic liquidation remains limited to the exact known maturity expression.

Old bond/order scripts and register schemas are a different protocol version and do not migrate in
place. This update recognizes the new pinned contracts; it does not settle old boxes with new rules.
The separately maintained web UI and generated public documentation need to adopt the API changes
when rebuilt.

## Validation

111 focused tests pass across contract pins, signed lifecycles, settlement verification, API/controllers,
batching, fee statistics and configuration. Regression coverage includes Long schedules, reserve refunds,
reordered output indices, shared history reads and splitting batches that exceed a signing limit.
The OpenAPI YAML parses and its fill-body references and Long-height schemas have been checked.
