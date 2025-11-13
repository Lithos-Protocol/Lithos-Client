package stratum.data;

import java.util.Properties;

public class Options {

	public Options(int extraNonce1Size, long difficultyMultiplier, long connectionTimeout, long blockRefreshInterval, String nodeApiUrl, Data data) {
		this.extraNonce1Size = extraNonce1Size;
		this.difficultyMultiplier = difficultyMultiplier;
		this.connectionTimeout = connectionTimeout;
		this.blockRefreshInterval = blockRefreshInterval;
		this.nodeApiUrl = nodeApiUrl;
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


	public static Options fromProperties(Properties properties) {
		return new Options(
				Integer.parseInt(properties.getProperty("extraNonce1Size")),
				Long.parseLong(properties.getProperty("difficultyMultiplier")),
				Long.parseLong(properties.getProperty("connectionTimeout")),
				Long.parseLong(properties.getProperty("blockRefreshInterval")),
				properties.getProperty("nodeApiUrl"),
				new Data()
		);
	}
}
