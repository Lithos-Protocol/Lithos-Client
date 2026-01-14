package stratum.data;

import org.bouncycastle.util.encoders.Hex;
import org.json.JSONObject;
import stratum.CollateralData;

import java.math.BigInteger;

public class MiningCandidate {

	public byte[] msg;
	public long height;
	public int version;
	public BigInteger b;
    public String pk;
    public JSONObject proof;
    public CollateralData collateralData;
	public MiningCandidate(byte[] msg, long height, int version, BigInteger b, String pk,
                           JSONObject proof, CollateralData collateralData) {
		this.msg = msg;
		this.height = height;
		this.version = version;
		this.b = b;
        this.pk = pk;
        this.proof = proof;
        this.collateralData = collateralData;
	}



	public static MiningCandidate fromJson(JSONObject obj, int version) {
		return new MiningCandidate(
				Hex.decode(obj.getString("msg")),
				obj.getInt("h"),
				version,
				obj.has("b") ? obj.getBigInteger("b") : null,
                obj.getString("pk"),
                obj.has("proof") ? obj.getJSONObject("proof") : null,
                null);
	}
    public static MiningCandidate fromJson(JSONObject obj, int version, CollateralData collateralData) {
        return new MiningCandidate(
                Hex.decode(obj.getString("msg")),
                obj.getInt("h"),
                version,
                obj.has("b") ? obj.getBigInteger("b") : null,
                obj.getString("pk"),
                obj.has("proof") ? obj.getJSONObject("proof") : null,
                collateralData);
    }
}
