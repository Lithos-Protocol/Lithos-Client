package stratum;

import org.ergoplatform.appkit.ErgoClient;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class NodeInterface {
    private final Logger logger = LoggerFactory.getLogger("NodeStratumBridge");
	private HttpRequest.Builder req(String path) {
		return HttpRequest.newBuilder(baseURI.resolve(path)).header("User-Agent", "stratum4ergo 1.0.0");
	}

	private final HttpClient http = HttpClient.newHttpClient();
	private final URI baseURI;

	public NodeInterface(String apiAddress) {
		baseURI = URI.create(apiAddress);
	}

	public boolean isOnline() {
		try {
			return http.send(req("/info").build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

	public JSONObject info() throws IOException {
		try {
			return new JSONObject(http.send(req("/info").build(), HttpResponse.BodyHandlers.ofString()).body());
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	public boolean isAddressValid(String address) {
		try {
			return new JSONObject(http.send(req("/utils/address/" + address).build(), HttpResponse.BodyHandlers.ofString()).body()).getBoolean("isValid");
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}



	public JSONObject miningCandidate(boolean useCollateral, String post, String apiKey) {
		try {
            if(!useCollateral) {
                JSONObject json = new JSONObject(http.send(req("/mining/candidate").build(), HttpResponse.BodyHandlers.ofString()).body());
                if (json.has("error")) {
                    logger.error("Error occurred while requesting mining candidate");
                    logger.error("errorCode: {}", json.getInt("error"));
                    logger.error("reason: {}", json.getString("reason"));
                    logger.error("details: {}", json.getString("detail"));
                    throw new RuntimeException("HTTP Request failed");
                } else
                    return json;
            }else{
                JSONObject json = new JSONObject(
                        http.send(req("/mining/candidateWithTxs")
                                .header("Content-Type", "application/json")
                                .header("api_key", apiKey)
                                .POST(HttpRequest.BodyPublishers.ofString("[" + post + "]"))
                                .build(), HttpResponse.BodyHandlers.ofString()).body()
                );
                if (json.has("error")) {
                    logger.error("Error occurred while requesting mining candidate with transactions");
                    logger.error("errorCode: {}", json.getInt("error"));
                    logger.error("reason: {}", json.getString("reason"));
                    logger.error("details: {}", json.getString("detail"));
                    throw new RuntimeException("HTTP Request failed");
                } else
                    return json;
            }
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	public boolean sendSolution(String nonce, String pk) {
		try {
			JSONObject postBody = new JSONObject();
			/*TODO FIX THIS CONSTANT*/
			postBody.put("pk", pk);
			postBody.put("w", "02a7955281885bf0f0ca4a48678848cad8dc5b328ce8bc1d4481d041c98e891ff3");
			postBody.put("n", nonce);
			postBody.put("d", 0);

			HttpResponse<String> response = http.send(req("/mining/solution").POST(HttpRequest.BodyPublishers.ofString(postBody.toString())).header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
			//System.out.println("postBody: " + postBody.toString());
			//System.out.println("Sent solution and got response " + response.statusCode());
			if(response.statusCode() == 500){
				System.out.println("response body: "+ response.body());
			}
			return response.statusCode() == 200;
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
}
