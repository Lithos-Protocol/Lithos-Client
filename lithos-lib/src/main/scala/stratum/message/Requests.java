package stratum.message;

import com.redbottledesign.bitcoin.rpc.stratum.MalformedStratumMessageException;
import com.redbottledesign.bitcoin.rpc.stratum.message.RequestMessage;
import org.json.JSONObject;

import java.util.List;

public final class Requests {
	private Requests() {}

	/** Parameter {@code index} as text, or "" when it is absent or JSON null. */
	static String param(List<Object> params, int index) {
		if (index >= params.size()) return "";
		Object value = params.get(index);
		return (value == null || JSONObject.NULL.equals(value)) ? "" : value.toString();
	}

	public static final class Subscribe extends RequestMessage {
		public static final String NAME = "mining.subscribe";

		public Subscribe(JSONObject jsonMessage) throws MalformedStratumMessageException {
			super(jsonMessage);
		}
	}

	public static final class Authorize extends RequestMessage {
		public static final String NAME = "mining.authorize";

		/** The password is optional: some clients send the worker name alone. */
		public final String workerName, password;

		public Authorize(JSONObject jsonMessage) throws MalformedStratumMessageException {
			super(jsonMessage);
			this.workerName = param(getParams(), 0);
			this.password = param(getParams(), 1);
		}
	}

	public static final class Submit extends RequestMessage {
		public static final String NAME = "mining.submit";

		public Submit(JSONObject jsonMessage) throws MalformedStratumMessageException {
			super(jsonMessage);
		}
	}

	/** A client announcing that it accepts mining.set_extranonce. */
	public static final class ExtranonceSubscribe extends RequestMessage {
		public static final String NAME = "mining.extranonce.subscribe";

		public ExtranonceSubscribe(JSONObject jsonMessage) throws MalformedStratumMessageException {
			super(jsonMessage);
		}
	}

	public static final class GetTransactions extends RequestMessage {
		public static final String NAME = "mining.get_transactions";

		public GetTransactions(JSONObject jsonMessage) throws MalformedStratumMessageException {
			super(jsonMessage);
		}
	}
}
