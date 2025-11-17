package stratum.message;

import com.redbottledesign.bitcoin.rpc.stratum.message.ResponseMessage;
import com.redbottledesign.bitcoin.rpc.stratum.message.Result;
import org.json.JSONObject;

/**
 * A response that is not sent to a request, but is sent due to other conditions. Seems like it was missed in the JStratum library.
 */
public class AnnouncementMessage extends ResponseMessage {
	protected final String method;

	public AnnouncementMessage(String method, Result params, String error) {
		super("", params, error);
		this.method = method;
	}

	public AnnouncementMessage(String method, Result params) {
		super("", params);
		this.method = method;
	}

	@Override
	public JSONObject toJson() {
		JSONObject obj = new JSONObject();

		Result result = this.getResult();
		String error = this.getError();
		obj.put("id", JSONObject.NULL);
		obj.put("method", method);
		//obj.put(JSON_STRATUM_KEY_ERROR, Objects.requireNonNullElse(error, JSONObject.NULL));
		obj.put("params", (result != null) ? result.toJson() : JSONObject.NULL);

		return obj;
	}
}
