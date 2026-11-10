Mainnet Machina Finance fills and orders, each the node's `/blockchain/transaction/byId` or
`/blockchain/box/byId` response verbatim. Each ERG/token fill spends an ErgoDEX native pool at input 0 and the order
at input 1, with no other input, and pays the pool, the order's child, the miner and the filler. The two token/token
grid fills have the same first two inputs, then the filler's own boxes.

| File | Id | Block | What |
|---|---|---|---|
| `limit-buys.json` | tx `5bd3e883c1f6…` | 1,904,970 | Current E2T limit buying 2,828 tokens at 35,000; recreated holding 1,020,000 nanoERG |
| `grid-sells.json` | tx `9454267212e2…` | 1,905,739 | E2T grid selling 3,131 tokens at its ask |
| `grid-buys.json` | tx `c15132cf2397…` | 1,905,007 | E2T grid buying 3,207 tokens at its bid |
| `grid-v2-sells.json` | tx `f9d946c40e03…` | 1,896,838 | E2T grid on the ErgoTree version 2 header (`1af401…`) selling 471 tokens at its ask of 31,348; its tree is the pinned grid's but for the header byte |
| `grid-v2-order.json` | box `6cfbcbfcbf70…` | 1,890,744 | Unfilled E2T grid on the version 2 header holding 119,800 of `limit-buys`'s token, asking 1,000,000 and bidding 10,000 |
| `limit-sell-order.json` | box `9ed8d4bf1aea…` | 1,903,888 | Unfilled current E2T limit selling 5,000 of `limit-buys`'s token at 45,233 |
| `old-limit-fill.json` | tx `4dba26c90b81…` | 1,892,431 | A fill of the older E2T limit, which is no longer filled |
| `ld-faku-pool.json` | box `54307bbc9a30…` | 1,912,934 | The LithosDex ERG/FAKU pool, under its own deployment; specs reshape its reserves |
| `t2t-grid-order.json` | box `84f59301ac49…` | 1,785,999 | The one token/token grid open on mainnet: 500 FAKU units, its base, and 200 of `ebb40eca…`; its ERG cannot cover its child |
| `faku-cig-t2t-pool.json` | box `dd4c3c8c2b6e…` | 1,912,895 | The ErgoDEX FAKU/`ebb40eca…` token pool |
| `faku-ergodex-pool.json` | box `730d34e5a5a7…` | 1,913,076 | The ErgoDEX ERG/FAKU pool, FAKU's deepest ERG pool |
| `t2t-grid-buys.json` | tx `ab08d4fb3ba8…` | 1,896,838 | A FAKU/SigUSD grid buying one SigUSD unit for 1,097 FAKU units, filled through the ERG/SigUSD pool with the filler's ERG |
| `t2t-grid-sells.json` | tx `56b1cc60f345…` | 1,895,970 | A FAKU/SigUSD grid selling one SigUSD unit for 569 FAKU units, filled through the ERG/FAKU pool with the filler's ERG |

The token/token grid boxes and their spends were read from a mainnet node's `/blockchain/box/byTemplateHash` and
`/blockchain/transaction/byId` on 2026-11-10; the pools from `/blockchain/box/unspent/byErgoTree`. The two version 2
grid files were read the same day: of the 63 unspent E2T grids then, 5 carried the version 2 header and 58 version 1.

The contracts are in the `machinafi/sdk` repository: the grids at `src/contracts/grid/`, and the current limit
at `src/contracts/limit/e2t-limit-order.es`.
