package stratum;


import stratum.data.MiningCandidate;
import org.bouncycastle.util.encoders.Hex;
import org.json.JSONArray;

import java.math.BigInteger;
import java.util.*;

import static stratum.Utils.jsonArray;

public class BlockTemplate {

	private static final BigInteger DIFF_1 = new BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000", 16);

	private static class Submission {

		public Submission(byte[] extraNonce1, byte[] extraNonce2, String nTime, byte[] nonce) {
			this.extraNonce1 = extraNonce1;
			this.extraNonce2 = extraNonce2;
			this.nTime = nTime;
			this.nonce = nonce;
		}

		public byte[] extraNonce1;
		public byte[] extraNonce2;
		public String nTime;
		public byte[] nonce;



		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Submission)) return false;
			else {
				Submission that = (Submission) o;
				return Arrays.equals(extraNonce1, that.extraNonce1) && Arrays.equals(extraNonce2, that.extraNonce2) && nTime.equals(that.nTime) && Arrays.equals(nonce, that.nonce);
			}
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(nTime);
			result = 31 * result + Arrays.hashCode(extraNonce1);
			result = 31 * result + Arrays.hashCode(extraNonce2);
			result = 31 * result + Arrays.hashCode(nonce);
			return result;
		}
	}

	private final Set<Object> submissions = new HashSet<>();

	public BlockTemplate(String jobId, MiningCandidate miningCandidate, boolean usedCollateral) {
		this.jobId = jobId;
		this.candidate = miningCandidate;
		this.target = miningCandidate.b;
		this.tau = BigInteger.valueOf(0);
		this.msg = miningCandidate.msg;
        this.usedCollateral = usedCollateral;
	}

	public BlockTemplate(String jobId, MiningCandidate miningCandidate, BigInteger tau,
                         boolean usedCollateral, boolean reducedShareMessages) {
		this.jobId = jobId;
		this.candidate = miningCandidate;
		this.target = miningCandidate.b;
		this.tau = tau;
		this.msg = miningCandidate.msg;
        this.usedCollateral = usedCollateral;
        this.reducedShareMessages = reducedShareMessages;
	}

	public MiningCandidate candidate;
	public String jobId;
	public BigInteger target;
	public BigInteger tau;
	public byte[] msg;
    public boolean usedCollateral;
    public boolean reducedShareMessages;

	public byte[] serializeCoinbase(byte[] extraNonce1, byte[] extraNonce2) {
		return Utils.concat(msg, extraNonce1, extraNonce2);
	}

	public boolean registerSubmit(byte[] extraNonce1, byte[] extraNonce2, String nTime, byte[] nonce) {
		return submissions.add(new Submission(extraNonce1, extraNonce2, nTime, nonce));
	}

	private JSONArray jobParams;

	public JSONArray getJobParams() {

		if (jobParams != null) return jobParams;
		return jobParams = jsonArray(
				jobId,
				candidate.height,
				Hex.toHexString(candidate.msg),
				"",
				"",
				Integer.toHexString(candidate.version),
                reducedShareMessages ? tau.divide(new BigInteger("1000")).toString() : tau.toString(),
				"",
				true
		);
	}
}
