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
	public MiningCandidate(byte[] msg, long height, int version, BigInteger b, String pk,
                           JSONObject proof) {
		this.msg = msg;
		this.height = height;
		this.version = version;
		this.b = b;
        this.pk = pk;
        this.proof = proof;
	}



	public static MiningCandidate fromJson(JSONObject obj, int version) {
		return new MiningCandidate(
				Hex.decode(obj.getString("msg")),
				obj.getInt("h"),
				version,
				obj.has("b") ? obj.getBigInteger("b") : null,
                obj.getString("pk"),
                obj.has("proof") ? obj.getJSONObject("proof") : null);
	}
}
