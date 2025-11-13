package stratum;

import cats.data.Op;
import stratum.data.Data;
import stratum.data.Options;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class CLI {
	// Test Stratum only
	private static final int extraNonce1Size = 4;
	private static final long difficultyMultiplier = 256;
	private static final long connectionTimeout = 60000;
	private static long blockRefreshInterval; // ms
	private static String nodeApiUrl = "http://127.0.0.1:9052/";
	private static int port = 4444;

	public static void main(String[] args) throws IOException {
		Properties properties = new Properties();
		//properties.load(new FileReader(args.length == 0 ? "cli.properties" : args[0], StandardCharsets.UTF_8));

		System.out.println("Hello" );
		Options options = new Options(extraNonce1Size, difficultyMultiplier, connectionTimeout,
				blockRefreshInterval, nodeApiUrl, new Data());
		ErgoStratumServer server = new ErgoStratumServer(options);

		System.out.println("Stratum server starting at port " + port);
		server.startListening(port);
	}
}
