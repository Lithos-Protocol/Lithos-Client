package stratum.message;

import com.redbottledesign.bitcoin.rpc.stratum.message.ResponseMessage;
import com.redbottledesign.bitcoin.rpc.stratum.message.Result;

import static stratum.Utils.jsonArray;

public class Response {

	/** [[["mining.set_difficulty", id], ["mining.notify", id]], extraNonce1, extraNonce2Size], the standard layout. */
	public static ResponseMessage2 subscribe(String id, String subscriptionId, String extraNonce1, int extraNonce2Size) {
		return new ResponseMessage2(id, () ->
				jsonArray(
					jsonArray(
						jsonArray("mining.set_difficulty", subscriptionId),
						jsonArray("mining.notify", subscriptionId)
					),
					extraNonce1,
					extraNonce2Size
				)
		);
	}

	public static ResponseMessage2 authorize(String id, boolean authorized, String error) {
		return new ResponseMessage2(id, () -> authorized, error);
	}

	public static ResponseMessage submit(String id, Result result, String error) {
		return new ResponseMessage2(id, result, error);
	}

	/** A plain {@code true}, for requests that only need acknowledging. */
	public static ResponseMessage2 acknowledge(String id) {
		return new ResponseMessage2(id, () -> true, null);
	}

	/** Refuses a method this stratum does not implement, echoing the request id as the client sent it. */
	public static ResponseMessage2 unsupported(String id, String method) {
		return new ResponseMessage2(id, null, "20: Method not supported: " + method);
	}
}
