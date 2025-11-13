package stratum;

import stratum.counter.ExtraNonceCounter;
import stratum.counter.JobCounter;
import stratum.data.*;
import org.bouncycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class JobManager {

	private static final BigInteger N_BASE = new BigInteger("2").pow(26);

	private static final long
			INCREASE_START = 600 * 1024,
			INCREASE_PERIOD_FOR_N = 50 * 1024,
			N_INCREASEMENT_HEIGHT_MAX = 9216000;

	private static final byte[] M;

	static {
		M = new byte[1024 * 8];
		for (int i = 0; i < 1024; i++) {
			System.arraycopy(Utils.longBytes(i), 0, M, i * 8, 8);
		}
	}

	private static BigInteger N(long height) {
		height = Math.min(N_INCREASEMENT_HEIGHT_MAX, height);
		if (height < INCREASE_START) {
			return N_BASE;
		} else if (height >= N_INCREASEMENT_HEIGHT_MAX) {
			return new BigInteger("2147387550");
		} else {
			BigInteger res = N_BASE;
			int iterationsNumber = (int) Math.floor((height - INCREASE_START) / (double) INCREASE_PERIOD_FOR_N) + 1;
			for (int i = 0; i < iterationsNumber; i++) {
				res = res.divide(new BigInteger("100").multiply(new BigInteger("105")));
			}
			return res;
		}
	}

	private static int[] generateIndexes(byte[] seed, long height) {
		byte[] hash = Utils.blake2b256(seed);
		byte[] extendedHash = {};
		try {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			outputStream.write(hash);
			outputStream.write(hash);

			extendedHash = outputStream.toByteArray();
		}catch(IOException e){
			System.out.println("Caught IO Exception");
		}
		ByteBuffer buf = ByteBuffer.wrap(extendedHash);
//		System.out.println("extended hash length: " + hash.length);
		return IntStream.range(0, 32).map(index -> {
			int x = buf.getInt(index);
//			System.out.println(x);

			return x % N(height).intValue();
		}).toArray();
	}

	private final JobCounter jobCounter = new JobCounter();

	public final ExtraNonceCounter extraNonceCounter;
	public final byte[] extraNoncePlaceholder = { (byte) 0xf0, 0x00, 0x00, 0x0f, (byte) 0xf1, 0x11, 0x11, 0x1f};
	public final int extraNonce2Size;

	public BlockTemplate currentJob;
	private final HashMap<String, BlockTemplate> validJobs = new HashMap<>();

	private final Options options;

	public JobManager(Options options) {
		extraNonceCounter = new ExtraNonceCounter(options.extraNonce1Size);
		this.options = options;
		extraNonce2Size = extraNoncePlaceholder.length - extraNonceCounter.size;
	}

	public void updateCurrentJob(MiningCandidate miningCandidate) {
		BlockTemplate blockTemplate = new BlockTemplate(
				jobCounter.next(),
				miningCandidate
		);

		this.currentJob = blockTemplate;

		triggerEvent(new UpdatedBlock(blockTemplate));

		validJobs.put(blockTemplate.jobId, blockTemplate);
	}

	/**
	 * @return whether a new block was processed
	 */
	public boolean processTemplate(MiningCandidate candidate) {
		boolean isNewBlock = currentJob == null;
		// Block is new if it's the first one seen or if the hash is different and the height is higher
		if(currentJob != null) {
			System.out.println("currentJobHeight: " + currentJob.candidate.height);
			System.out.println("currentJobMsg: " + Hex.toHexString(currentJob.candidate.msg));
			System.out.println("templateMsg: " + Hex.toHexString(candidate.msg));
		}

		if (!isNewBlock && !Arrays.equals(currentJob.candidate.msg, candidate.msg)) {
			System.out.println("Msg not equal");
			isNewBlock = true;

			// If new block is outdated/out-of-sync then return
			if (candidate.height < currentJob.candidate.height)
				return false;
		}

		if (!isNewBlock) return false;

		BlockTemplate blockTemplate = new BlockTemplate(
				jobCounter.next(),
				candidate
		);

		currentJob = blockTemplate;

		validJobs.clear();

		triggerEvent(new NewBlock(blockTemplate));

		validJobs.put(blockTemplate.jobId, blockTemplate);
		System.out.println("New block template processed with diff: " + candidate.b);
		return true;
	}

	public static class ProcessingException extends Exception {
		private final int id;

		public ProcessingException(int id, String message) {
			super(message);
			this.id = id;
		}

		public int getId() { return id; }
	}

	private interface ThrowException {
		void run(int id, String msg) throws ProcessingException;
	}

	public byte[] processShare(String jobId, BigInteger difficulty, byte[] extraNonce1, byte[] extraNonce2, String nTime, String ipAddress, int port, String workerName) throws ProcessingException {
		ThrowException shareError = (errorId, errorMessage) -> {
			triggerEvent(new Share(new Fail(
					jobId,
					ipAddress,
					workerName,
					difficulty,
					errorMessage
			), null));
			throw new ProcessingException(errorId, errorMessage);
		};

		if (extraNonce2.length != extraNonce2Size) {
//			System.out.println("extraNonce2Length" + extraNonce2.length);
//			System.out.println("extraNonce2asddLength " + extraNonceCounter.size);
//			System.out.println("extraNonce2Size" + extraNonce2Size);
			shareError.run(20, "incorrect size of extraNonce2");
			return null;
		}

		BlockTemplate job = validJobs.get(jobId);

		if (job == null) {
			shareError.run(21, "job not found");
			return null;
		}

		byte[] nonce = Utils.concat(extraNonce1, extraNonce2);

		if (nonce.length != 8) {
			shareError.run(20, "incorrect size of nonce");
			return null;
		}

		if (!job.registerSubmit(extraNonce1, extraNonce2, nTime, nonce)) {
			shareError.run(22, "duplicate share");
			return null;
		}

		byte[] coinbase = job.serializeCoinbase(extraNonce1, extraNonce2);

		byte[] h = Utils.intBytes((int) job.candidate.height);
		byte[] i = Utils.intBytes((int) BigInteger.valueOf(Utils.longValue(Arrays.copyOfRange(Utils.blake2b256(coinbase), 24, 32))).remainder(N(job.candidate.height)).longValue());
		byte[] e = Arrays.copyOfRange(Utils.blake2b256(Utils.concat(
				i, h, M
		)), 1, 32);
		List<byte[]> J = Arrays.stream(generateIndexes(Utils.concat(e, coinbase), job.candidate.height)).mapToObj(Utils::intBytes).collect(Collectors.toList());
		BigInteger f = J.stream().map(item -> new BigInteger(Arrays.copyOfRange(Utils.blake2b256(Utils.concat(item, h, M)), 1, 32)))
				.reduce(BigInteger::add)
				.orElseThrow();
		BigInteger fh = new BigInteger(Utils.blake2b256(Utils.padStart(f.toByteArray(), 32)));
		byte[] blockHash;
		// Check if share is a block candidate (matched network difficulty)
		if (job.candidate.b.compareTo(fh) >= 0) {
			// Must submit solution
			blockHash = Utils.padStart(f.toByteArray(), 32);
		} else {
			// Check if share didn't reach the miner's difficulty
			if (new BigInteger(job.getJobParams().getString(6)).multiply(difficulty).compareTo(fh) <= 0) {
				shareError.run(32, "Low difficulty share");
				return null;
			}
			blockHash = new byte[0];
		}

		triggerEvent(new Share(new Success(
				jobId,
				ipAddress,
				workerName,
				difficulty,
				job.candidate.height,
				job.candidate.msg,
				1,
				false,
				job.difficulty,
				blockHash,
				false
		), nonce));

		return blockHash;
	}

	// Event listener stuff

	private final HashMap<Class<? extends JobManagerEvent>, List<Consumer<? extends JobManagerEvent>>> eventListeners = new HashMap<>();

	public <T extends JobManagerEvent>void addEventListener(Class<T> type, Consumer<T> consumer) {
		Objects.requireNonNull(type, "type");
		eventListeners.putIfAbsent(type, new ArrayList<>());
		eventListeners.get(type).add(consumer);
	}

	public <T extends JobManagerEvent>void removeEventListener(Class<T> type, Consumer<T> consumer) {
		Objects.requireNonNull(type, "type");
		if (eventListeners.containsKey(type)) {
			eventListeners.get(type).remove(consumer);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private <T extends JobManagerEvent>void triggerEvent(T event) {
		if (eventListeners.containsKey(event.getClass())) {
			for (Consumer<? extends JobManagerEvent> consumer : eventListeners.get(event.getClass())) {
				((Consumer) consumer).accept(event);
			}
		}
	}
}
