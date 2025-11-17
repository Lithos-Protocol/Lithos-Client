package stratum.message;

import com.redbottledesign.bitcoin.rpc.stratum.message.Result;
import org.json.JSONArray;
import stratum.BlockTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;

import static stratum.Utils.jsonArray;

public class Announcement {

	public static AnnouncementMessage difficulty(BigDecimal difficulty) {
		return new AnnouncementMessage("mining.set_difficulty", () -> jsonArray(difficulty));
	}

	public static AnnouncementMessage miningJob(BlockTemplate blockTemplate) {
		return new AnnouncementMessage("mining.notify", blockTemplate::getJobParams);
	}
}
