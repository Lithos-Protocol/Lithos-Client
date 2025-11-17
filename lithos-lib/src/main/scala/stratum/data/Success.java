package stratum.data;

import java.math.BigDecimal;
import java.math.BigInteger;

public class Success extends ShareData {
    public Success(String jobId, String ipAddress, String workerName, BigInteger difficulty,
                   long height, byte[] msg, long shareDiff, boolean blockDiff, BigInteger blockDiffActual,
                   byte[] blockHash, boolean blockHashInvalid) {
        super(jobId, ipAddress, workerName, difficulty);
        this.height = height;
        this.msg = msg;
        this.shareDiff = shareDiff;
        this.blockDiff = blockDiff;
        this.blockDiffActual = blockDiffActual;
        this.blockHash = blockHash;
        this.blockHashInvalid = blockHashInvalid;
    }

    public long height;
    public byte[] msg;
    public long shareDiff;
    public boolean blockDiff;
    public BigInteger blockDiffActual;
    public byte[] blockHash;
    public boolean blockHashInvalid;

}
