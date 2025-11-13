package stratum.data;

import java.math.BigInteger;

public abstract class ShareData {
	public ShareData(String jobId, String ipAddress, String workerName, BigInteger difficulty) {
		this.jobId = jobId;
		this.ipAddress = ipAddress;
		this.workerName = workerName;
		this.difficulty = difficulty;
	}

	public String jobId;

	public String ipAddress;

	public String workerName;

	public BigInteger difficulty;
}

