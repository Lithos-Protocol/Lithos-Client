# Lithos Reference Client
Lithos Protocol is a decentralized mining pool protocol which uses smart contracts to evaluate miner's work and pay them
accordingly. Lithos uses Non-Interactive Share Proofs (NISPs) to efficiently prove a miner's work.


## Requirements
In order to run Lithos, you must have a working Ergo node. To mine on the Lithos pool, you may use any mining software which
 supports Ergo's Autolykos 2 algorithm. We recommend using [SOAT Miner](https://github.com/blindrun/soat-miner), as it is open-source
with 0 dev fee and built-in Lithos support. Lithos releases require a working Java 11 installation.

### Overall System Requirements:
- 6-8GB of RAM
- An Ergo node (versions 6.0.7/6.1.7 and above)
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

Once your difficulty is committed, it can take up to 28 hours for subsequent changes to take effect.
Make sure you have the right value before committing!

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
To calculate the right difficulty, the webpanel provides a difficulty calculator you can
use to find the right value for your hashrate. The calculator is found at `localhost:9000/assets/mining/difficulty`

Input your hashrate, and select the **start** difficulty. Leave **Account for network difficulty** ticked:
it sizes the recommendations to how long windows actually last on the network, rather than a flat 2 hours.
![DiffCalc](/docs/DiffCalc.png)

In the image above, at 100 MH/s, it is `4.24M`. Once you've obtained your starting difficulty,
you have two options to commit it onto the blockchain.

### WebPanel
Go to `localhost:9000/assets/mining/difficulty/commitment`. There you can see the commitment
page, which lets you set your difficulty commitment via the API. Input your diff and Lithos API Key
in order send a transaction which commits your difficulty onto the blockchain.
![CommitmentPage](/docs/Commitments.png)
### AutoCommit
To make your difficulty commitment, set `stratum.diff` to the appropriate value,
and then set `state.autoCommit = true`. 

For both options the wallet associated with your node and Lithos Client must have some ERG to send the
commitment transaction. We recommend a minimum of **0.05 ERG** to start. This should cover commitments and NISP bonds which
are needed for submissions. Miners with higher hashrate amounts may need a bit more ERG.

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
It listens on every network interface unless `stratum.bindAddress` names one address, e.g. `127.0.0.1`
for rigs on the same machine.
If you are using SOAT Miner, you can use the `--lithos` option to set up your miner to mine to your Lithos Client.


### Alternative Mining Clients
Lithos works with all mining software. However, we cannot strictly recommend other mining software
due to having dev fees and being closed source. The Lithos Client has been tested and known to work
with Rigel Miner.
```
rigel.exe -a autolykos2 -o stratum+tcp://127.0.0.1:4444 -u YOUR_ERG_WALLET -w my_rig --log-file logs/miner.log
```
Keep in mind that the `ERG_WALLET` and Worker name have no effect on Lithos, and can be set to any valid String.

## Block Transactions
A block you find also carries fee-less transactions the client builds for itself, configured per source under
`stratum.candidate.sources` in `application.conf`.

### Upkeep
Upkeep is maintenance of other protocols' boxes carried in your own block: boxes whose script says when one is
due and what its successor is, so that anyone may advance them with no key. Storage rent is the same idea applied
to Ergo's own four-year rule; upkeep applies it to a registry of reviewed jobs, one per protocol, each advancing
only the boxes it maintains.

It is **off by default**, and so is every job. To run it, enable the source and then the jobs you want:
```hocon
stratum.candidate.sources.upkeep.enabled = true
stratum.candidate.sources.upkeep.jobs.heartbeat.enabled = true
```
The first job, `heartbeat`, advances due-job boxes (`DueJob.ergo`, kept with the tests; the client carries only its
tree) and pays their tip to your collection output. By default a beat must pay at least a thousandth of an ERG
(`minTip`); set it to 0 to maintain boxes that cannot pay. Finding them by script needs a node started with `ergo.node.extraIndex = true`. Any node also
reads the boxes listed in `jobs.heartbeat.boxIds` (every job takes `boxIds` the same way); on a plain node that
list is all the job sees, and it goes stale after each beat, because a beat gives the box a new id. A job name
that is enabled and unknown is refused at startup.

Before each successor is offered, your node checks it (`verifyWithNode = true`), and one the node refuses is left
out. Until your miner finds a block there is nothing to see upkeep do, so it has an observe mode:
```hocon
stratum.candidate.sources.upkeep.mode = "observe"
```
Upkeep then answers every block request empty and, in the background, builds what it would have offered, asks
your node to check each transaction (up to `maxTxs` checks per block) and logs the verdict. Observe mode keeps no
memory; it runs only while the stratum builds block transactions, with the source enabled, `maxTxs` above 0 and a job
enabled, and a height that arrives while the previous check is still running is skipped. The
heartbeat has boxes to observe on testnet (see below), none on mainnet yet.

**Creating a due-job box.** A due-job box is any box at the heartbeat's script holding three registers: R4 the
height of the last beat (`Int`), R5 the period in blocks (`Int`, positive), R6 the tip per beat in nanoERG (`Long`,
not negative). The script's address is `BLeBj4M5DTjaKjyEUwPF8JE7b6E4haYuHXwms5Rhf7mCThPdAnTdwPBHVTugWGXSgRzyt156JiWMSp31zuw3J3Vagvw1XGHQf1K7AUULYm64u8yWm6qVNpBQ3vBUWZS3tBkAExSYVFWz54M38YaZbVPLnPUx9HMNpnPiqzTz6Si1AQCPKUXUJS14aCTFc4TfF6RiqeEn2jUfH`
on testnet and `2gXTNpGs3kytbjbWSS8G5uhdZCm9CkBBFKVz1jQ5uosE3S4JmWuQmoCHuxvTEP8ybf8syM8z17h5oe26FkKcMUDgFH1tE2Ru8QwAfrG1qp94rwr2CcB7pz79M6WYhTeRzW36LcA5QX7zGZvqPREduzSoX1NL7Zn2JLusiPyUreTvwpC5iyBEAcaP9tfLF4rLVMz5SvFfV68FG`
on mainnet (the tree `HeartbeatJob.TreeHex`, the same on both). Fund it with the tips you want paid plus the box's
own minimum. Once it is paid down to its minimum it is beaten for free, or declined by miners that keep the default
`minTip`; each beat restamps its creation height, so storage rent takes it only if nobody beats it for four years.
Registers of other types, a zero period, a negative tip, an R4 + R5 beyond 2,147,483,647, a box funded at exactly its
minimum with a small R4 (its successor's R4 takes more bytes), or a box within a few bytes of the 4,096-byte limit lock the box until
storage rent: there is no owner and no exit, so check the registers before sending. On testnet, box
`e5d9d2c29f7be9914c604c8102c6f08839cdd01c006c312c1596c144fe6d8fe1` (period 720, tip 0.01 ERG) is live.

The shipped `heartbeat` job never spends your ERG: it spends only boxes at its own script, signs with a prover that
holds no key, and pays no fee. The client also refuses any job's transaction that spends a box the job did not report
and read back, or one at your wallet's keys (its P2PK and miner-reward scripts). Upkeep's only use of the mempool is to skip a box a pending transaction already
spends; it never reads what pending transactions do, and it never builds on unconfirmed outputs. A box whose build fails is set aside and tried again after
`retryAfterScans` discovery passes; a box that cannot pay its successor is set aside until it changes.

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


