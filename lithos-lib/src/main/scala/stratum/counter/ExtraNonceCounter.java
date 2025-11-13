package stratum.counter;

import org.bouncycastle.util.encoders.Hex;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

public class ExtraNonceCounter {

	public final int size;

	private int counter;

	public ExtraNonceCounter(int size) {
		if (size < 1 || size > 4)
			throw new IllegalArgumentException("size must be in range [1-4)");
		this.size = size;
		counter = new SecureRandom().nextInt() << 27;
	}

	public ExtraNonceCounter() {
		this(4);
	}

	public String next() {
		return Hex.toHexString(ByteBuffer.allocate(4).putInt(Math.abs(counter++)).array()).substring(8 - 2 * size);
	}

}
