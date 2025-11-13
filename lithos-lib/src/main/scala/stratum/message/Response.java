package stratum.message;

import com.redbottledesign.bitcoin.rpc.stratum.message.ResponseMessage;
import com.redbottledesign.bitcoin.rpc.stratum.message.Result;

import static stratum.Utils.jsonArray;

public class Response {

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
}
