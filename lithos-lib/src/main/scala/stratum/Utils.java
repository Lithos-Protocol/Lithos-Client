package stratum;

import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class Utils {

	public static JSONArray jsonArray(Object... content) {
		JSONArray array = new JSONArray();
		for (Object o : content) {
			array.put(o);
		}
		return array;
	}

	public static byte[] concat(byte[]... arrays) {
		byte[] merged = new byte[Arrays.stream(arrays).mapToInt(a -> a.length).sum()];
		int pos = 0;
		for (byte[] array : arrays) {
			System.arraycopy(array, 0, merged, pos, array.length);
			pos += array.length;
		}
		return merged;
	}

	public static void reverse(ByteBuffer buffer) {
		if (buffer.capacity() % 2 != 0) throw new IllegalArgumentException();

		for (int i = 0; i < buffer.capacity() / 2; i++) {
			byte b = buffer.get(i);
			buffer.put(i, buffer.get(buffer.capacity() - 1 - i));
			buffer.put(buffer.capacity() - 1 - i, b);
		}
	}

	/** @apiNote big-endian */
	public static byte[] intBytes(int value) {
		return new byte[] {
				(byte)(value >> 24),
				(byte)(value >> 16),
				(byte)(value >> 8),
				(byte)value};
	}

	/** @apiNote big-endian */
	public static byte[] longBytes(long value) {
		return new byte[] {
				(byte)(value >> 56),
				(byte)(value >> 48),
				(byte)(value >> 40),
				(byte)(value >> 32),
				(byte)(value >> 24),
				(byte)(value >> 16),
				(byte)(value >> 8),
				(byte)value
		};
	}

	/** @apiNote little-endian */
	public static byte[] longBytesLittle(long value) {
		return new byte[] {
				(byte)value,
				(byte)(value >> 8),
				(byte)(value >> 16),
				(byte)(value >> 24),
				(byte)(value >> 32),
				(byte)(value >> 40),
				(byte)(value >> 48),
				(byte)(value >> 56)
		};
	}

	/** @apiNote big-endian */
	public static long longValue(byte[] bytes) {
		long value = 0L;

		for (byte b : bytes) {
			value = (value << 8) + (b & 255);
		}

		return value;
	}

	public static byte[] blake2b256(byte[] input) {
		return new Blake2b.Blake2b256().digest(input);
	}

	public static byte[] padStart(byte[] bytes, int targetLength) {
		int needed = targetLength - bytes.length;
		if (needed == 0) return bytes;
		if (needed < 0) throw new IllegalArgumentException("byte array is larger than target");
		byte[] b = new byte[targetLength];
		System.arraycopy(bytes, 0, b, targetLength - bytes.length, bytes.length);
		return b;
	}
}
