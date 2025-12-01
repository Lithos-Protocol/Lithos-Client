package stratum;

import lfsm.LFSMHelpers;
import nisp.NISP$;
import nisp.NISPDatabase;
import nisp.SuperShare;
import org.ergoplatform.appkit.ErgoClient;
import org.ergoplatform.appkit.ErgoProver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.Tuple3;
import scala.math.BigInt;
import scorex.utils.Ints;
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
    private final Logger logger = LoggerFactory.getLogger("StratumPool");
    private boolean useCollateral = false;
    private ErgoClient client = null;
    private String pk = null;
    private ErgoProver prover = null;
    private String apiKey = null;
    private boolean reducedShareMessages = false;
	public Pool(Options options, ErgoStratumServer server) {

		this.options = options;
		this.server = server;

		setupJobManager();
	}

    public Pool(Options options, ErgoStratumServer server,
                boolean withCollateral, ErgoClient client, ErgoProver prover,
                String apiKey, boolean reducedShareMessages) {

        this.options = options;
        this.server = server;
        this.useCollateral = withCollateral;
        this.client = client;
        this.prover = prover;
        this.apiKey = apiKey;
        this.reducedShareMessages = reducedShareMessages;
        setupJobManager();

    }

	public void start() throws IOException {
		nodeInterface = new NodeInterface(options.nodeApiUrl);
		if (!nodeInterface.isOnline())
			throw new ConnectException("node is offline");
		JSONObject info = nodeInterface.info();
		options.data.protocolVersion = info.getJSONObject("parameters").getInt("blockVersion");
		options.data.chainDifficulty = info.getBigInteger("difficulty").multiply(BigInteger.valueOf(options.difficultyMultiplier));
		setupJobManager();
		getBlockTemplate();
		Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
			try {
				//System.out.println("Searching for block");
				if (getBlockTemplate()) {
					logger.info("Found block with polling");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, 0, options.blockRefreshInterval, TimeUnit.MILLISECONDS);
	}
    //Starts pool, but lets thread scheduling be handled by outside source
    public void startThirdParty() throws IOException {
        logger.info("Starting StratumPool with third party scheduling");
        nodeInterface = new NodeInterface(options.nodeApiUrl);
        if (!nodeInterface.isOnline())
            throw new ConnectException("node is offline");
        JSONObject info = nodeInterface.info();
        options.data.protocolVersion = info.getJSONObject("parameters").getInt("blockVersion");
        options.data.chainDifficulty = info.getBigInteger("difficulty").multiply(BigInteger.valueOf(options.difficultyMultiplier));
        setupJobManager();
        getBlockTemplate();
    }
    public void setupJobManager() {

		jobManager = new JobManager(options);

		jobManager.addEventListener(NewBlock.class, e -> {
			server.broadcastMiningJob(e.blockTemplate);
		});
		jobManager.addEventListener(UpdatedBlock.class, e -> {
			server.broadcastMiningJob(e.blockTemplate);
		});
		jobManager.addEventListener(Share.class, e -> {
			ShareData shareData = e.shareData;
			var isValidShare = shareData instanceof Success;
			//System.out.println("share: " + shareData.difficulty());
			if (isValidShare) {
                Success successfulShare = ((Success) shareData);
                if(successfulShare.isBlock) {
                    submitBlock(shareData, e.nonce);
                    if (getBlockTemplate())
                        logger.info("New block found after submission!");
                }
                if(successfulShare.isSuperShare) {
                    SuperShare share = SuperShare.fromCandidate(e.nonce, successfulShare.candidate);
                    logger.info("Saving super share for block {}", share.getHeight());
                    NISPDatabase nispDB = new NISPDatabase();
                    long score = LFSMHelpers.convertTauOrScore(BigInt.apply(successfulShare.difficulty)).longValue();
                    boolean success = nispDB.addNISP(share.getHeight(), score, share);
                    if(success){
                        logger.info("Successfully saved super share");
                        logger.info("NISP-DB: {} entries, lastHeight: {}, currentHeight: {}",
                                nispDB.size(), // TODO: Potentially expensive call, consider removing later
                                Ints.fromByteArray(nispDB.lastHeight().get()),
                                Ints.fromByteArray(nispDB.currentHeight().get())
                        );
                    }else{
                        // Failure to save super-share directly affects payments,
                        // and should be treated as a critical error
                        throw new RuntimeException("Failed to save super share to NISP database");
                    }

                }

			}
		});
	}

    public void submitBlock(ShareData shareData, byte[] nonce) {
        logger.info("Submitted block with nonce: {}", Hex.toHexString(nonce));
		nodeInterface.sendSolution(Hex.toHexString(nonce), pk);

	}

    public boolean getBlockTemplate() {
        MiningCandidate candidate = null;
        boolean usedCollateral = useCollateral;
        if(!useCollateral) {
            candidate = MiningCandidate.fromJson(
                    nodeInterface.miningCandidate(false, null, null),
                    options.data.protocolVersion // unused
            );
        }else{
            try {
                //TODO If script fails on node-side, does pk get reset?
                CollateralRetriever retriever = new CollateralRetriever(client, prover);
                Tuple3<String, String, String> tuple = retriever.getCollateral();
                candidate = MiningCandidate.fromJson(
                        nodeInterface.miningCandidate(true, tuple._2(), apiKey),
                        options.data.protocolVersion, tuple._1()
                        // unused
                );
                pk = candidate.pk; // TODO: Use candidate.pk until collateral is fixed
            } catch (Exception e) {
                //e.printStackTrace();
                logger.error(e.getMessage());
                logger.error("Defaulting to solo-mining in order to get block templates");
                candidate = MiningCandidate.fromJson(
                        nodeInterface.miningCandidate(false, null, null),
                        options.data.protocolVersion // unused
                );
                pk = candidate.pk;
                usedCollateral = false;
            }
        }
        if(pk == null){
            pk = candidate.pk;
        }
		return jobManager.processTemplate(candidate, options.tau, usedCollateral, reducedShareMessages);
	}
}
