# Lithos Reference Client
Lithos Protocol is a decentralized mining pool protocol which uses smart contracts to evaluate miner's work and pay them
accordingly. Lithos uses Non-Interactive Share Proofs (NISPs) to efficiently prove a miner's work.

## Requirements
In order to run Lithos, you must have a working Ergo node. To mine on a Lithos pool, you may use any mining software, but we
recommend [Rigel Miner](https://github.com/rigelminer/rigel). Lithos releases require a working Java 11 installation.
## Instructions
Before running the Lithos client, you will need a fully synced node.  For testnet purposes follow this guide to set-up a testnet node: [Testnet Node Tutorial](https://github.com/Lithos-Protocol/Lithos-Client/blob/master/TestnetNode.md)

To run the client, download a release `.zip` file. Unzip the file,
and navigate to `lithos-client-[version]/conf`. Open up the config file `application.conf` and input relevant data for
your node in the `node` section. An example of this section is shown below.
```
  node {
    url = "127.0.0.1"
    key = "NODE_API_KEY_HERE"
    storagePath = "path/to/nodefolder/.ergo/wallet/keystore/keyfile.json"
    pass        = "NODE_WALLET_PASS_HERE"
    networkType = "TESTNET"
    explorerURL = "https://api-testnet.ergoplatform.com"
  }
```

After setting the node configuration, you must set
```
play.http.secret.key="NEW_SECRET"
```
to some new secret value. The value is not used by the current testnet release, but will be needed in the future
when using the HTML Panel.

After setting up your config file, ensure that your node is running before executing the start script in
`lithos-client-[version]/bin`. This script will start the Lithos Client.

## Stratum
Once your client has started, it will attempt to sync to the blockchain and will spin up a
stratum server for you to connect your miner to. In Lithos, miners are not paid via shares. Instead, miners submit NISPs to the blockchain, and are paid according to the
difficulty value associated with the NISP. To change your difficulty value, change the stratum configuration located in
`application.conf`

```
stratum {
    diff = "4.0G" # Format diff as (value)(powTen), e.g "4.0G", "1.52M", "300.1K", etc.
    stratumPort = 4444
    extraNonce1Size = 4
    connectionTimeout = 60000
    blockRefreshInterval = 1000
    reduceShareMessages  = false
  }
```
The above configuration would mine at difficulty "4G". In the *Rigel Miner*, connecting to a Lithos stratum would require
the following command:

```
rigel.exe -a autolykos2 -o stratum+tcp://127.0.0.1:4444 -u YOUR_ERG_WALLET -w my_rig --log-file logs/miner.log
```
Keep in mind that the `ERG_WALLET` and Worker name have no effect on Lithos, and can be set to any valid String.

### Share Messaging
If you are running at a difficulty that causes your miner to send too many shares, you may get errors relating to
"duplicate" or "potentially old" shares. To reduce the amount of shares sent between the mining software
and the stratum, set `reduceShareMessages` to `true`. This will raise the stratum difficulty by 1000 times without affecting the
difficulty that Lithos evaluates your shares at.

### Super Shares
When mining, you will get messages relating to super shares. Super shares are used to evaluate how much
work you performed. As a Lithos miner, your goal is to create at least **10 super shares within
a 12-hour window before the block was mined**. The amount of super shares you create is directly related to your
chosen `diff` value and your hashrate. Increasing your `diff` value will decrease the amount of super shares you create.
Likewise, decreasing your `diff` value will allow you to create more super shares. On the testnet, we recommend trying
different values for your `diff` to see how super share creation functions with your hardware. All super shares you mine
will be stored in the `.lithos` folder, which is generated when you mine your first super share. 

## KYA
The Lithos Testnet release accesses your node's secret keys via it's keystore in order to sign and generate transactions.
We **heavily** recommend that you generate a new secret key for testnet which is not related to any mainnet wallets you
own. This may change on future releases.


## Testnet
The current testnet release (`1.0-SNAPSHOT`) is for testing the stratum, rollup syncing, and basic contract transformations.
Because of this, NISP storage and submission are disabled at the moment, but will be re-enabled next week when fraud proof
contracts are added. Since no NISPs are evaluated, the Lithos client will attempt to submit work any time a block is found on the
network.

## Acknowledgments
Big thanks to the creator of [Rigel Miner](https://github.com/rigelminer/rigel) for helping me with some Stratum issues.
Also thanks to [Satergo](https://github.com/Satergo) for creating the [stratum4ergo](https://github.com/Satergo/stratum4ergo) repo which the Lithos stratum implementation heavily takes from.  


