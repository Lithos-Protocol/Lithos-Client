package stratum.counter;

import stratum.Utils;
import org.bouncycastle.util.encoders.Hex;


public class SubscriptionIdCounter {

	private long count;

	public String next() {
		count++;
		if (count == Long.MAX_VALUE)
			count = 1;
		return "deadbeefcafebabe" + Hex.toHexString(Utils.longBytesLittle(count));
	}
}
