Mainnet Machina Finance fills and orders, each the node's `/blockchain/transaction/byId` or
`/blockchain/box/byId` response verbatim. Each fill spends an ErgoDEX native pool at input 0 and the order at
input 1, with no other input, and pays the pool, the order's child, the miner and the filler.

| File | Id | Block | What |
|---|---|---|---|
| `limit-buys.json` | tx `5bd3e883c1f6…` | 1,904,970 | Current E2T limit buying 2,828 tokens at 35,000; recreated holding 1,020,000 nanoERG |
| `grid-sells.json` | tx `9454267212e2…` | 1,905,739 | E2T grid selling 3,131 tokens at its ask |
| `grid-buys.json` | tx `c15132cf2397…` | 1,905,007 | E2T grid buying 3,207 tokens at its bid |
| `limit-sell-order.json` | box `9ed8d4bf1aea…` | 1,903,888 | Unfilled current E2T limit selling 5,000 of `limit-buys`'s token at 45,233 |
| `old-limit-fill.json` | tx `4dba26c90b81…` | 1,892,431 | A fill of the older E2T limit, which is no longer filled |

The contracts are in the `machinafi/sdk` repository: the grids at `src/contracts/grid/`, and the current limit
at `src/contracts/limit/e2t-limit-order.es`.
