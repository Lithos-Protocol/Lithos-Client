package stratum;

import stratum.data.ShareData;

public class Share extends JobManagerEvent {
    public ShareData shareData;
    public byte[] nonce;
    public Share(ShareData shareData, byte[] nonce) {
        super();
        this.shareData = shareData;
        this.nonce = nonce;
    }
}
