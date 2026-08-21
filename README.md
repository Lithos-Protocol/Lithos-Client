# Lithos Reference Client
Lithos Protocol is a decentralized mining pool protocol which uses smart contracts to evaluate miner's work and pay them
accordingly. Lithos uses Non-Interactive Share Proofs (NISPs) to efficiently prove a miner's work.

## Requirements
In order to run Lithos, you must have a working Ergo node. To mine on the Lithos pool, you may use any mining software which
 supports Ergo's Autolykos 2 algorithm. We recommend using [SOAT Miner](https://github.com/blindrun/soat-miner), as it is open-source
with 0 dev fee and built-in Lithos support. Lithos releases require a working Java 11 installation.
## Instructions
Before running the Lithos client, you will need a fully synced node.  For testnet purposes follow this guide to set-up a testnet node: [Testnet Node Tutorial](https://github.com/Lithos-Protocol/Lithos-Client/blob/master/TestnetNode.md)

To run the client, download a release `.zip` file. Unzip the file,
and navigate to `lithos-client/bin`. Create a new file called `lithos.conf` and input the following
into it:
```
{
  include file("../conf/application.conf")
  node {
    url = "127.0.0.1"
    key = "NODE_API_KEY_HERE"
    storagePath = "path/to/nodefolder/.ergo/wallet/keystore/keyfile.json"
    pass        = "NODE_WALLET_PASS_HERE"
    networkType = "TESTNET"
    explorerURL = "https://api-testnet.ergoplatform.com"
    # Number of addresses managed by Lithos. Used for 
    numAddresses = 32
  }
  # Change this value to a secret key
  play.http.secret.key="changethissecret"
  # Hash of "hello"
  lithos.apiKeyHash="324dcf027dd4a30a932c441f365a25e86b173defa4b8e58948253471b81b72cf"
  stratum {
    diff = "140M" # Format diff as (value)(powTen), e.g "4.0G", "1.52M", "300.1K", etc.
    stratumPort = 4444
    
    # Reduces shares sent between mining software and Lithos client
    reduceShareMessages = true
    # Used for testing different difficulties
    forceConfigDiff = false
  }
  state.autoCommit = true
}
```


After setting up your config file, ensure that your node is running before executing the start script in
`lithos-client/bin`. This script will start the Lithos Client.

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


