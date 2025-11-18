# Setting Up an Ergo Testnet Node for Lithos

Lithos requires a fully synced Ergo Testnet node with mining enabled.  
This document explains how to install, configure, and run the node on both Windows and Linux.

---

## 1. Overview

Lithos relies on your local Ergo Testnet node to:

- Sync the blockchain  
- Sign transactions using the node wallet keystore  
- Provide block and header data for rollup evaluation  
- Expose a REST API Lithos communicates with  

The node must be running and fully synced before Lithos can operate.

---

## 2. Download the Ergo Testnet Node

You may either download the latest prebuilt Testnet node JAR or build it from the required pull request branch.

### Option A: Prebuilt Testnet Node JAR

The latest Testnet-enabled node build is provided here:

https://t.me/ErgoDevelopers/36855

Download the JAR file from the message contents and place it in your node directory.

### Option B: Build From Source (Pull Request 2252)

---
https://github.com/ergoplatform/ergo/pull/2252
---

## 3. Create the Node Directory

Choose a directory for the node files.

### Windows Example

```
C:\ergo-testnet\
```

### Linux Example

```
/opt/ergo-testnet/
```

Place the node JAR inside this folder and create a configuration file named:

```
ergo.conf
```

---

## 4. Create the Configuration File (`ergo.conf`)

Inside the node directory, create `ergo.conf` with the following contents:

```hocon
ergo { 
  networkType = "testnet"
  node {
    useExternalMiner = true
    offlineGeneration = false
    mining = true
    extraIndex = true
  }
}

scorex {
  restApi {
    # Hash of API key "hello"
    apiKeyHash = "324dcf027dd4a30a932c441f365a25e86b173defa4b8e58948253471b81b72cf"
  }
  network {
    knownPeers = [
      "128.253.41.110:9020",
      "213.239.193.208:9023"
    ]
    nodeName = "lithos-testnet-node"
  }
}
```

The REST API key for this configuration is:

```
hello
```

This key must later be provided to Lithos.

---

## 5. Running the Ergo Testnet Node

### Windows Instructions

1. Verify Java 11 is installed:

```powershell
java -version
```

If the version is not 11, install a JDK 11 distribution such as Temurin or Zulu.

2. Start the node:

```powershell
cd C:\ergo-testnet
java -jar ergo-testnet.jar --testnet -c ergo.conf
```

Adjust the JAR filename if necessary.

---

### Linux Instructions

1. Install Java 11:

```bash
sudo apt update
sudo apt install openjdk-11-jdk -y
```

2. Start the node:

```bash
cd /opt/ergo-testnet
java -jar ./ergo-testnet.jar --testnet -c ergo.conf
```
Example with testnet jar (For easy copy / pasta)

```bash
cd /opt/ergo-testnet
java -jar ergo-6.0.1-1-91aa8056-SNAPSHOT.jar --testnet -c ergo.conf
---

## 6. Accessing the Node Panel and Swagger

Once the node is running, you can access:

### Node Panel (Web UI)
```
http://127.0.0.1:9053/panel
```

### Swagger API Explorer
```
http://127.0.0.1:9053/swagger
```

The node panel provides:

- Sync status
- Peers
- Wallet status
- Blockchain info
- Mining info

Swagger provides:

- Full API documentation
- Wallet endpoints
- Explorer endpoints
- Node control endpoints

Both will work only while the node is running.

---

## 7. Creating a Wallet Using the Panel (Recommended)

The easiest way to create a Testnet wallet is through the node’s built-in Panel UI.

### Steps:

1. Open:
   ```
   http://127.0.0.1:9053/panel
   ```
2. Go to the **Wallet** tab.
3. Click **Create Wallet**.
4. Set your wallet password.
5. Save your mnemonic (testnet only).
6. A keystore file will be generated automatically.

The keystore file will be stored here:

### Windows
```
C:\ergo-testnet\data\wallet\keystore\keyfile.json
```

### Linux
```
/opt/ergo-testnet/data/wallet/keystore/keyfile.json
```

---

## 8. Creating a Wallet Using Swagger (Alternative Method)

If you prefer to use the REST API directly, you may initialize the wallet via Swagger.

Open Swagger:

```
http://127.0.0.1:9053/swagger
```

Navigate to:

```
/wallet/init
```

Enter:

- `api_key`: `hello`
- JSON body for password creation

This produces the same keystore file as the Panel method.

---

## 9. Testing Node REST API

Whether or not you created your wallet through the panel, verify the node API is working.

### Windows

```powershell
curl http://127.0.0.1:9053/info
```

### Linux

```bash
curl http://127.0.0.1:9053/info
```

Expected output:

```json
{
  "fullHeight": 123456,
  "isMining": true,
  "headersHeight": 123456
}
```

If the response is valid, your node is configured correctly.

---

## 10. Node Setup Complete

Your Ergo Testnet node is now:

- Installed  
- Configured  
- Running  
- Synced  
- Wallet created (via Panel or Swagger)  
- Accepting REST API calls  

Lithos can now be configured and started using this node.


