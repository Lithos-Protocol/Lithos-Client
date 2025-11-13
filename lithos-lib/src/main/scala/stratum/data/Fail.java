package stratum.data;

import java.math.BigInteger;

public class Fail extends ShareData {

    public String error;
    public Fail(String jobId, String ipAddress, String workerName, BigInteger difficulty, String error) {
        super(jobId, ipAddress, workerName, difficulty);
        this.error = error;
    }
}
