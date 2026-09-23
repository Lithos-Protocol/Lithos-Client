package stratum;


import lfsm.LFSMHelpers;
import scala.math.BigInt;
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
		this(jobId, miningCandidate, BigInteger.valueOf(0), usedCollateral, false);
	}

	/** Reduces by the NISP coefficient, so reduced reporting sends miners the super-share threshold. */
	public BlockTemplate(String jobId, MiningCandidate miningCandidate, BigInteger tau,
                         boolean usedCollateral, boolean reducedShareMessages) {
		this(jobId, miningCandidate, tau, usedCollateral, reducedShareMessages, LFSMHelpers.NISP_COEFFICIENT());
	}

	public BlockTemplate(String jobId, MiningCandidate miningCandidate, BigInteger tau,
                         boolean usedCollateral, boolean reducedShareMessages, int reductionMultiplier) {
		this.jobId = jobId;
		this.candidate = miningCandidate;
		this.target = miningCandidate.b;
		this.tau = tau;
		this.msg = miningCandidate.msg;
        this.usedCollateral = usedCollateral;
        this.reducedShareMessages = reducedShareMessages;
        // Derived once per job rather than per share. Both are two BigInteger divisions over values
        // that cannot change while the job lives, and share validation is the hottest path here.
        if (tau.signum() > 0) {
            this.realTau = LFSMHelpers.convertTauOrScore(
                    LFSMHelpers.convertTauOrScore(BigInt.apply(tau))).bigInteger();
            this.superShareThreshold = realTau.divide(BigInteger.valueOf(LFSMHelpers.NISP_COEFFICIENT()));
        } else {
            this.realTau = BigInteger.ZERO;
            this.superShareThreshold = BigInteger.ZERO;
        }
        // Reduced reporting hands miners tau cut by the reduction multiplier; at the NISP coefficient
        // every share they then send is a super-share. Both the notify and the work accounting read
        // this one field, because a miner submits at whatever it was told.
        this.assignedThreshold = reducedShareMessages
                ? tau.divide(BigInteger.valueOf(reductionMultiplier))
                : tau;
        // Built here, not on first read. It used to be a lazily-populated non-final field that every
        // connection actor reads from its own thread when serialising a mining.notify, so a miner
        // could be handed a half-constructed array.
        this.jobParams = buildJobParams();
	}

	public MiningCandidate candidate;
	public String jobId;
	public BigInteger target;
	public BigInteger tau;
	public byte[] msg;
    public boolean usedCollateral;
    public boolean reducedShareMessages;

    /** The miner's assigned difficulty as a score, and the super-share cut of it. */
    public final BigInteger realTau;
    public final BigInteger superShareThreshold;

    /**
     * The threshold this job actually advertises. One accepted share is worth TARGET_MAX divided by
     * this, which is the only correct per-share work: a miner submits what it was told to submit.
     */
    public final BigInteger assignedThreshold;

	public byte[] serializeCoinbase(byte[] extraNonce1, byte[] extraNonce2) {
		return Utils.concat(msg, extraNonce1, extraNonce2);
	}

	public boolean registerSubmit(byte[] extraNonce1, byte[] extraNonce2, String nTime, byte[] nonce) {
		return submissions.add(new Submission(extraNonce1, extraNonce2, nTime, nonce));
	}

	private final JSONArray jobParams;

	public JSONArray getJobParams() {
		return jobParams;
	}

	private JSONArray buildJobParams() {
		return jsonArray(
				jobId,
				candidate.height,
				Hex.toHexString(candidate.msg),
				"",
				"",
				Integer.toHexString(candidate.version),
                assignedThreshold.toString(),
				"",
				true
		);
	}
}
