The hex fixtures are the published ErgoTrees from the native-pool v1
[deposit](https://github.com/spectrum-finance/ergo-dex/blob/master/contracts/amm/cfmm/v1/n2t/Deposit.sc)
and [redeem](https://github.com/spectrum-finance/ergo-dex/blob/master/contracts/amm/cfmm/v1/n2t/Redeem.sc)
contracts, and the token-pool v1
[swap](https://github.com/spectrum-finance/ergo-dex/blob/master/contracts/amm/cfmm/v1/t2t/Swap.sc),
[deposit](https://github.com/spectrum-finance/ergo-dex/blob/master/contracts/amm/cfmm/v1/t2t/Deposit.sc)
and [redeem](https://github.com/spectrum-finance/ergo-dex/blob/master/contracts/amm/cfmm/v1/t2t/Redeem.sc)
contracts (`*-t2t-v1.hex`). Tests substitute order constants and assert the pinned template before signing.

`t2t/` holds four mainnet executions of token-pool v1 orders, each the node's
`/blockchain/transaction/byId` response verbatim: `swap-x-in` (`b357619919dc…`, block 1,853,807),
`swap-y-in` (`e6e9368076a1…`, 1,853,147), `deposit` (`f302719c7e46…`, 1,835,367) and `redeem`
(`a72ca9dc081e…`, 1,842,549). Each spends the pool at input 0 and the order at input 1.
