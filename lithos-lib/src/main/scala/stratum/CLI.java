package stratum;

import cats.data.Op;
import stratum.data.Data;
import stratum.data.Options;

import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class CLI {
	// Test Stratum only
	private static final int extraNonce1Size = 4;
	private static final long difficultyMultiplier = 256;
	private static final long connectionTimeout = 60000;
	private static long blockRefreshInterval = 1000; // ms
	private static String nodeApiUrl = "http://127.0.0.1:9052/";
	private static int port = 4444;
	//Diff 4G
	private static BigInteger tau4G = new BigInteger("28948022309329048855892746252171976963209391069768726095651290785380");

	public static void main(String[] args) throws IOException {

		Options options = new Options(extraNonce1Size, difficultyMultiplier, connectionTimeout,
				blockRefreshInterval, nodeApiUrl, getTauAsDiff(20, 6), new Data());
		ErgoStratumServer server = new ErgoStratumServer(options);

		System.out.println("Stratum server starting at port " + port);
		server.startListening(port);
	}
	// Diff is in G (10^9)
	public static BigInteger getTauAsDiff(double diff, int powOfTen){
		BigDecimal diffValue = BigDecimal.valueOf(diff).scaleByPowerOfTen(powOfTen);
		BigDecimal targetMax = BigDecimal.valueOf(2).pow(256);
		BigDecimal result = targetMax.divide(diffValue, 2, RoundingMode.DOWN);
		return result.toBigInteger();
	}
}
