package stratum.data;

import java.math.BigInteger;

public class Options {

	public Options(int extraNonce1Size, long difficultyMultiplier, long connectionTimeout, long blockRefreshInterval,
				   String nodeApiUrl, BigInteger tau, Data data) {
		this.extraNonce1Size = extraNonce1Size;
		this.difficultyMultiplier = difficultyMultiplier;
		this.connectionTimeout = connectionTimeout;
		this.blockRefreshInterval = blockRefreshInterval;
		this.nodeApiUrl = nodeApiUrl;
		this.tau = tau;
		this.data = data;

		if (!nodeApiUrl.endsWith("/"))
			throw new IllegalArgumentException("nodeApiUrl must end with a slash");
	}

	public int extraNonce1Size;
	public long difficultyMultiplier;
	public long connectionTimeout;
	public long blockRefreshInterval; // ms
	public String nodeApiUrl;
	public Data data;
	public BigInteger tau;
}
