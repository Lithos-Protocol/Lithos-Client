# Lithos Reference Client
Lithos Protocol is a decentralized mining pool protocol which uses smart contracts to evaluate miner's work and pay them
accordingly. Lithos uses Non-Interactive Share Proofs (NISPs) to efficiently prove a miner's work.


## Requirements
In order to run Lithos, you must have a working Ergo node. To mine on the Lithos pool, you may use any mining software which
 supports Ergo's Autolykos 2 algorithm. We recommend using [SOAT Miner](https://github.com/blindrun/soat-miner), as it is open-source
with 0 dev fee and built-in Lithos support. Lithos releases require a working Java 11 installation.

### Overall System Requirements:
- 6-8GB of RAM
- ~30GB of storage (for the Ergo node)
- Java 11

The GPU you use to mine does not need to be on the same machine as the client.
Whichever GPU you use, make sure its compatible with Autolykos 2 VRAM requirements.

## Instructions (Mainnet Pre-Release)
Before running the Lithos client, you will need a fully synced, indexed node.\
For setting up a node, follow the node guide, which gives instructions for testnet and mainnet
nodes: [Node Tutorial](https://github.com/Lithos-Protocol/Lithos-Client/blob/master/TestnetNode.md)

To run the client, download a release `.zip` file. Unzip the file,
and navigate to `lithos-client/bin`. Create a new file called `lithos.conf` and input the following
into it:
```hocon
{
  include file("../conf/application.conf")
  node {
    url = "127.0.0.1"
    key = "NODE_API_KEY_HERE"
    storagePath = "path/to/nodefolder/.ergo/wallet/keystore/keyfile.json"
    pass        = "NODE_WALLET_PASS_HERE"
    networkType = "MAINNET"
    explorerURL = "default"
    numAddresses = 32
  }
  # Change this value to a secret key
  play.http.secret.key="changethissecret"
  # Hash of "hello"
  lithos.apiKeyHash="324dcf027dd4a30a932c441f365a25e86b173defa4b8e58948253471b81b72cf"
  
}
```


After setting up your config file, ensure that your node is running before executing the start script in
`lithos-client/bin`. This script will start the Lithos Client.

## Batching (Mainnet Pre-Release)
The Mainnet Pre-release does not support features outside LithosDex and order batching. To
control parameters regarding these features, use the following config settings:
```hocon
  # Order discovery and execution settings for batching adapters.
  batching {
    ergodex {
      enabled = true
      # Milliseconds between confirmed-order scans.
      scanIntervalMs = 8000
      # Maximum order ids retained between scans; boxes are reloaded before execution.
      maxTrackedOrders = 512
      # Maximum pools tracked, newest first.
      maxTrackedPools = 256
      # Transaction slots per candidate build or broadcast pass, including carried ancestors.
      maxOrdersPerBlock = 20
      # Minimum gross revenue per fill, in nanoERG. An opening fill must also fund a takings box.
      minRevenueNanoErg = 1000000
      # Minimum gross revenue for broadcasts; net takings must remain positive after the miner fee.
      broadcastMinRevenueNanoErg = 2500000
      # Broadcast miner fee ceiling in nanoERG, further limited by the order's own cap.
      broadcastMinerFeeCeiling = 2000000
      # Skip pools whose current box was included more than this many blocks ago.
      maxPoolAgeBlocks = 20160
      # Broadcast orders using executor revenue for fees. Requires the ErgoDEX source enabled.
      broadcast = true

      # With broadcast on, also broadcast orders still in the mempool, spending the unconfirmed order box.
      # Off, a broadcast waits for an order to confirm and be indexed. Your own block reads them either way.
      broadcastMempoolOrders = true

      # Pool NFTs never to execute against.
      deniedPools = []
      # Milliseconds an order that priced but could not be built is left out of scans and builds.
      skippedOrderTtlMs = 3600000
      # Most such orders remembered at once, oldest forgotten first. Each costs about 200 bytes of memory,
      # so the default holds under 1 MB. 0 remembers none, and a bad order is retried on every build.
      maxSkippedOrders = 4096
      # Unconfirmed transactions one order may need carried into your block ahead of it: the transaction
      # that placed it and every unconfirmed ancestor of that one. An order needing more waits for them to
      # confirm. Each carried transaction is one more that can fail your run. 0 executes confirmed orders only.
      maxAncestorTxs = 2

      # Unconfirmed orders one build or broadcast pass takes, and how many of them any single transaction
      # may contribute. They are taken in rounds across the transactions that created them, so a
      # transaction carrying hundreds of order boxes takes maxMempoolOrdersPerTx places, not all of them.
      # Only the orders taken are parsed, which is what this bounds.
      maxMempoolOrders = 64
      maxMempoolOrdersPerTx = 4
      # Orders a run prices but fails to build before it stops trying more. Each failure is one wasted signature.
      maxUnbuildablePerRun = 32
      # Orders from one creating transaction a run fails to build before it passes over that transaction's
      # other orders untried. Keeps one spam transaction from using up maxUnbuildablePerRun on its own.
      maxUnbuildablePerTx = 2
    }
    # LithosDex orders against the single ERG:LIT pool. Runs whether or not this node mines:
    # broadcasting needs no stratum, and candidates additionally need stratum.candidate.sources.lithosdex.
    lithosdex {
      enabled = true
      # Milliseconds between confirmed-order scans.
      scanIntervalMs = 8000
      # Maximum order ids retained between scans; boxes are reloaded before execution.
      maxTrackedOrders = 512
      # Transaction slots per candidate build or broadcast pass, including carried ancestors.
      maxOrdersPerBlock = 20
      # Minimum executor fee per order, in nanoERG. An opening fill must also fund a takings box.
      minRevenueNanoErg = 1000000
      # Minimum executor fee for broadcasts; net takings must remain positive after the miner fee.
      broadcastMinRevenueNanoErg = 2500000
      # Broadcast miner fee ceiling in nanoERG, further limited by the order's own cap.
      broadcastMinerFeeCeiling = 2000000
      # Broadcast orders, paying miner fees out of executor fees. Your ERG is never spent.
      broadcast = true

      # With broadcast on, also broadcast orders still in the mempool, spending the unconfirmed order box.
      # Off, a broadcast waits for an order to confirm and be indexed. Your own block reads them either way.
      broadcastMempoolOrders = true
      # Close each candidate run with a flush, moving the pool's pending fees to the vault so providers
      # can claim them. It pays nothing and costs nothing in your own block. Never broadcast.
      autoFlush = true
      # Milliseconds an order that priced but could not be built is left out of scans and builds.
      skippedOrderTtlMs = 3600000
      # Most such orders remembered at once, oldest forgotten first. Each costs about 200 bytes of memory,
      # so the default holds under 1 MB. 0 remembers none, and a bad order is retried on every build.
      maxSkippedOrders = 4096
      # Unconfirmed transactions one order may need carried into your block ahead of it: the transaction
      # that placed it and every unconfirmed ancestor of that one. An order needing more waits for them to
      # confirm. Each carried transaction is one more that can fail your run. 0 executes confirmed orders only.
      maxAncestorTxs = 2

      # Unconfirmed orders one build or broadcast pass takes, and how many of them any single transaction
      # may contribute. They are taken in rounds across the transactions that created them, so a
      # transaction carrying hundreds of order boxes takes maxMempoolOrdersPerTx places, not all of them.
      # Only the orders taken are parsed, which is what this bounds.
      maxMempoolOrders = 64
      maxMempoolOrdersPerTx = 4
      # Orders a run prices but fails to build before it stops trying more. Each failure is one wasted signature.
      maxUnbuildablePerRun = 32
      # Orders from one creating transaction a run fails to build before it passes over that transaction's
      # other orders untried. Keeps one spam transaction from using up maxUnbuildablePerRun on its own.
      maxUnbuildablePerTx = 2
    }
  }
  # Fees on LithosDex orders you place through the API or web panel, when the request does not set its
  # own. Both are paid only when a miner fills the order; a cancelled order pays neither.
  lithosdex {
    orders {
      # nanoERG you pay whoever fills each order. Miners skip orders paying less than they accept, so an
      # order under that waits. The default clears the shipped settings of every miner running this client.
      executorFeeNanoErg = 3000000
      # Most of the executor fee a miner may spend as a network fee when it broadcasts the fill. Must be
      # below executorFeeNanoErg. It comes out of the executor fee, never out of your funds.
      maxMinerFeeNanoErg = 1000000
    }
  }
```
Make sure to add them before the final closing bracket `}` in your config file.


---
# Testnet Settings
The following information applies only to testnet users, where Lithos is fully operational.

## Synchronization & Mining
Once your Lithos Client starts, you will likely want to wait before mining. The Lithos Client will start
synchronizing from the `startHeight` set in `application.conf`. You can also override
it in your own conf by placing `state.startHeight = NEW_START_HEIGHT_HERE`.

Additionally, you will not be able to receive mining rewards until you make a difficulty commitment on the blockchain.
You can think of a difficulty commitment as a promise to mine at a certain hashrate. You will be able to make
a difficulty commitment when your client has fully synced the `MinerDictionary` to the current
state of the blockchain. This can take around 30 minutes on the current testnet. Once it is synced,
your client will make a transaction to commit to the difficulty set in your config file(the `diff` value).

If you would like to experiment before commiting to a difficulty, you can set
`forceConfigDiff = true` and `state.autoCommit = false` in your config. This will force your
Lithos Client to always use the diff set in your config, and will ensure that any experimental values
are not committed to on the blockchain.

### Super Shares
When mining, you will get messages relating to super shares. Super shares are used to evaluate how much
work you performed. As a Lithos miner, your goal is to create **10 super shares within
a 12-hour (3 hours on testnet) window before the block was mined**. The amount of super shares you create is directly related to your
chosen `diff` value and your hashrate. 

Increasing your `diff` value will decrease the amount of super shares you create.
Likewise, decreasing your `diff` value will allow you to create more super shares. On the testnet, we recommend trying
different values for your `diff` to see how super share creation functions with your hardware. All super shares you mine
will be stored in the `.lithos` folder, which is generated when you mine your first super share.

#### REMEMBER
More super-shares does not mean higher payouts! The amount you are paid is controlled
entirely by your `diff`, higher values pay you out more. However, if you set your `diff` too
high, you may not create enough super-shares within the window. Your goal as a miner is to balance
these two variables, and find the right proportion of risk and reward.

## Stratum
The Lithos Client will run a local stratum server at `stratum.stratumPort` (`4444` by default).
If you are using SOAT Miner, you can use the `--lithos` option to set up your miner to mine to your Lithos Client.
SOAT Miner also comes included with a `mine_ergo_lithos` script which handles things for you.

### Alternative Mining Clients
Lithos works with all mining software. However, we cannot strictly recommend other mining software
due to having dev fees and being closed source. The Lithos Client has been tested and known to work
with Rigel Miner.
```
rigel.exe -a autolykos2 -o stratum+tcp://127.0.0.1:4444 -u YOUR_ERG_WALLET -w my_rig --log-file logs/miner.log
```
Keep in mind that the `ERG_WALLET` and Worker name have no effect on Lithos, and can be set to any valid String.

## KYA
The Lithos Testnet release accesses your node's secret keys via it's keystore in order to sign and generate transactions.
We **heavily** recommend that you generate a new secret key for testnet which is not related to any mainnet wallets you
own. This may change on future releases.


## Security
Lithos uses your node's wallet and api keys to interact with the blockchain and create transactions for you. It is
not recommended to directly expose your Lithos Client's API outside your local network.

### Avoiding Plaintext Key Storage
If you would like to avoid storing private information such as your node's api key and wallet password
in the plaintext config file, you can use environment variables for better security.

```
node.key = ${?NODE_KEY_ENV}
node.pass = ${?NODE_PASS_ENV}
play.http.secret.key=${?PLAY_ENV}
```
Placing these lines at the bottom of your config will read your node key, wallet pass, and play secret from the
`NODE_KEY_ENV`, `NODE_PASS_ENV`, and `PLAY_ENV` environment variables.
## Acknowledgments
Big thanks to the creator of [Rigel Miner](https://github.com/rigelminer/rigel) for helping me with some Stratum issues initially.
Also, big thanks to [SOAT Miner](https://github.com/blindrun/soat-miner) for creating an open-source, no dev-fee miner for Ergo with built-in Lithos support.
Also thanks to [Satergo](https://github.com/Satergo) for creating the [stratum4ergo](https://github.com/Satergo/stratum4ergo) repo which the Lithos stratum implementation heavily takes from.  


