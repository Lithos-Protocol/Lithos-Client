package stratum.data;

import java.math.BigInteger;

public class Success extends ShareData {
    /**
     *
     * @param difficulty Tau value we are working at
     * @param height Height of this block
     * @param msg Hash of header without PoW
     * @param shareDiff Actual value of the share, in tau representation
     * @param isBlock Whether or not this share constitutes a block
     * @param blockDiffActual Actual target of the blockchain which represents real blocks
     * @param blockHash Hash of the block
     * @param isSuperShare Whether or not this share constitutes a super-share
     * @param candidate Candidate used for this share
     */
    public Success(String jobId, String ipAddress, String workerName, BigInteger difficulty,
                   long height, byte[] msg, BigInteger shareDiff, boolean isBlock, BigInteger blockDiffActual,
                   byte[] blockHash, boolean isSuperShare, MiningCandidate candidate) {
        super(jobId, ipAddress, workerName, difficulty);
        this.height = height;
        this.msg = msg;
        this.shareDiff = shareDiff;
        this.isBlock = isBlock;
        this.blockDiffActual = blockDiffActual;
        this.blockHash = blockHash;
        this.isSuperShare = isSuperShare;
        this.candidate = candidate;
    }

    public long height;
    public byte[] msg;
    public BigInteger shareDiff;
    public boolean isBlock;
    public BigInteger blockDiffActual;
    public byte[] blockHash;
    public boolean isSuperShare;
    public MiningCandidate candidate;

}
