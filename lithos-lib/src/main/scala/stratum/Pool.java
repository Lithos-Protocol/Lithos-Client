package stratum;

import stratum.data.MiningCandidate;
import stratum.data.Options;
import stratum.data.ShareData;
import stratum.data.Success;
import org.bouncycastle.util.encoders.Hex;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Pool {

	private final Options options;
	private final ErgoStratumServer server;

	public JobManager jobManager;
	private NodeInterface nodeInterface;

	public Pool(Options options, ErgoStratumServer server) {

		this.options = options;
		this.server = server;

		setupJobManager();
	}

	public void start() throws IOException {
		nodeInterface = new NodeInterface(options.nodeApiUrl);
		if (!nodeInterface.isOnline())
			throw new ConnectException("node is offline");
		JSONObject info = nodeInterface.info();
		options.data.protocolVersion = info.getJSONObject("parameters").getInt("blockVersion");
		options.data.difficulty = info.getBigInteger("difficulty").multiply(BigInteger.valueOf(options.difficultyMultiplier));
		setupJobManager();
		getBlockTemplate();
		Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
			try {
				System.out.println("Searching for block");
				if (getBlockTemplate()) {
					System.out.println("Found block with polling");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, 0, options.blockRefreshInterval, TimeUnit.MILLISECONDS);
	}

	private void setupJobManager() {

		jobManager = new JobManager(options);

		jobManager.addEventListener(NewBlock.class, e -> {
			server.broadcastMiningJob(e.blockTemplate);
		});
		jobManager.addEventListener(UpdatedBlock.class, e -> {
			server.broadcastMiningJob(e.blockTemplate);
		});
		jobManager.addEventListener(Share.class, e -> {
			ShareData shareData = e.shareData;
			var isValidBlock = shareData instanceof Success;
			//System.out.println("share: " + shareData.difficulty());
			if (isValidBlock) {
				submitBlock(shareData, e.nonce);
				if (getBlockTemplate())
					System.out.println("New block found after submission");
			}
		});
	}

	private void submitBlock(ShareData shareData, byte[] nonce) {
		System.out.println("Submitted block with nonce: " + Hex.toHexString(nonce));
		//TODO Change this constant
		nodeInterface.sendSolution(Hex.toHexString(nonce), "02a7955281885bf0f0ca4a48678848cad8dc5b328ce8bc1d4481d041c98e891ff3");

	}

	private boolean getBlockTemplate() {
		JSONObject miningCandidate = nodeInterface.miningCandidate();
		System.out.println(miningCandidate.toString());
		MiningCandidate candidate = MiningCandidate.fromJson(
				nodeInterface.miningCandidate(),
				options.data.protocolVersion // unused
		);
		System.out.println("candidate: " + candidate.b + " height: " + candidate.height);
		//System.out.println("b: " + candidate.b() + " | height: " + candidate.height());
		return jobManager.processTemplate(candidate);
	}
}
