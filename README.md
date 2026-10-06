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

## Instructions

We recommend using [Lithos Launcher](https://github.com/Lithos-Protocol/Lithos-Launcher) to set up the Lithos
Client and Ergo Node. Follow the instructions there to get set up.

If you prefer to set things up separately, use the instructions below.
## Instructions (Without Launcher)
Before running the Lithos client, you will need a fully synced, indexed node.\
For setting up a node, follow the node guide, which gives instructions for testnet and mainnet
nodes: [Node Tutorial](https://github.com/Lithos-Protocol/Lithos-Client/blob/master/TestnetNode.md)

Once your node is set up, you can download the latest release `.zip` file here:
[Lithos Releases](https://github.com/Lithos-Protocol/Lithos-Client/releases)
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


Make sure to add them before the final closing bracket `}` in your config file.

## Synchronization & Mining
Once your Lithos Client starts, you will likely want to wait before mining. The Lithos Client will start
synchronizing from the `startHeight` set in `application.conf`. You can also override
it in your own conf by placing `state.startHeight = NEW_START_HEIGHT_HERE`.

Additionally, you will not be able to receive mining rewards until you make a difficulty commitment on the blockchain.
You can think of a difficulty commitment as a promise to mine at a certain hashrate. You will be able to make
a difficulty commitment when your client has fully synced the `MinerDictionary` to the current
state of the blockchain. Once a commitment is made, you should wait **65 blocks**(2 hours on mainnet) before starting to mine.
You will get paid for any blocks found **125 blocks** after your commitment (4 hours on mainnet). It is recommended
to start mining at 65 blocks so that you can build up your super shares.

### Super Shares
When mining, you will get messages relating to super shares. Super shares are used to evaluate how much
work you performed. As a Lithos miner, your goal is to create **10 super shares within
a 2-hour (45 minutes on testnet) window before the block was mined**. The amount of super shares you create is directly related to your
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

## Making Your Diff Commitment
To make your difficulty commitment, set `stratum.diff` to the appropriate value,
and then set `state.autoCommit = true`. The wallet associated with your node and Lithos Client
must have at least 0.05 ERG to make the commitment on-chain.

In order to find the right difficulty, you can use the difficulty calculator hosted by the
Lithos Client as `localhost:9000/assets/mining/difficulty`

Input your hashrate, and select the **start** difficulty.
![DiffCalc](/docs/DiffCalc.png)

In the image below, it is `4.8M`. Input that value into `stratum.diff` and then turn on
`state.autoCommit` to make your commitment.

### Monitoring Super Shares
A good way to check if things are working properly is to view the Super Shares panel.
You can view this panel at `localhost:9000/assets/mining`

When you first start mining, the panel will look like this:
![NoSuperShares](/docs/NoSuperShares.png)

Once you've found 10 super shares, your panel will look like this:
![SuperSharesFound](/docs/SuperSharesFound.png)

If you used the **start** diff, you should see "ready" most of the time. If the panel
is not showing "ready" often or at all, you should consider lowering your difficulty.

## Stratum
The Lithos Client will run a local stratum server at `stratum.stratumPort` (`4444` by default).
If you are using SOAT Miner, you can use the `--lithos` option to set up your miner to mine to your Lithos Client.


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


