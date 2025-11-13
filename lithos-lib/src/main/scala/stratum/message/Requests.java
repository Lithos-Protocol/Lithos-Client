package stratum.message;

import com.redbottledesign.bitcoin.rpc.stratum.MalformedStratumMessageException;
import com.redbottledesign.bitcoin.rpc.stratum.message.RequestMessage;
import org.json.JSONObject;

public final class Requests {
	private Requests() {}

	public static final class Subscribe extends RequestMessage {
		public static final String NAME = "mining.subscribe";

		public Subscribe(JSONObject jsonMessage) throws MalformedStratumMessageException {
			super(jsonMessage);
		}
	}

	public static final class Authorize extends RequestMessage {
		public static final String NAME = "mining.authorize";

		public final String workerName, password;

		public Authorize(JSONObject jsonMessage) throws MalformedStratumMessageException {
			super(jsonMessage);
			this.workerName = (String) getParams().get(0);
			this.password = (String) getParams().get(1);
		}
	}

	public static final class Submit extends RequestMessage {
		public static final String NAME = "mining.submit";

		public Submit(JSONObject jsonMessage) throws MalformedStratumMessageException {
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
