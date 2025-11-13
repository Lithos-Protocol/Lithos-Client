package stratum.counter;

public class JobCounter {

	private int counter = 0;

	public String next() {
		counter++;
		if (counter % 0xFFFF == 0)
			counter = 1;
		return current();
	}

	public String current() {
		return Integer.toHexString(counter);
	}
}
