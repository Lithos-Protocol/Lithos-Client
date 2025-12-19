package stratum.data;

import org.bouncycastle.util.encoders.Hex;
import org.json.JSONObject;

import java.math.BigInteger;

public class MiningCandidate {

	public byte[] msg;
	public long height;
	public int version;
	public BigInteger b;
    public String pk;
    public JSONObject proof;
    public String txId;
    public byte[] txBytes;
	public MiningCandidate(byte[] msg, long height, int version, BigInteger b, String pk,
                           JSONObject proof, String txId, byte[] txBytes) {
		this.msg = msg;
		this.height = height;
		this.version = version;
		this.b = b;
        this.pk = pk;
        this.proof = proof;
        this.txId = txId;
        this.txBytes = txBytes;
	}



	public static MiningCandidate fromJson(JSONObject obj, int version) {
		return new MiningCandidate(
				Hex.decode(obj.getString("msg")),
				obj.getInt("h"),
				version,
				obj.has("b") ? obj.getBigInteger("b") : null,
                obj.getString("pk"),
                obj.has("proof") ? obj.getJSONObject("proof") : null,
                null,
                null);
	}
    public static MiningCandidate fromJson(JSONObject obj, int version, String txId, byte[] txBytes) {
        return new MiningCandidate(
                Hex.decode(obj.getString("msg")),
                obj.getInt("h"),
                version,
                obj.has("b") ? obj.getBigInteger("b") : null,
                obj.getString("pk"),
                obj.has("proof") ? obj.getJSONObject("proof") : null,
                txId,
                txBytes);
    }
}
