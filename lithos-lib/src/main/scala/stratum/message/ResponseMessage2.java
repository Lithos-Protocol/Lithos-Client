package stratum.message;

import com.redbottledesign.bitcoin.rpc.stratum.MalformedStratumMessageException;
import com.redbottledesign.bitcoin.rpc.stratum.message.Message;
import com.redbottledesign.bitcoin.rpc.stratum.message.ResponseMessage;
import com.redbottledesign.bitcoin.rpc.stratum.message.Result;
import com.redbottledesign.bitcoin.rpc.stratum.message.ResultFactory;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * <p>Java representation of a Stratum response message.</p>
 *
 * <p>Response messages must include the following:</p>
 *
 * <ul>
 *  <li>an {@code id} field, which cannot be {@code null} and must match the
 *      identifier that was specified in the request.</li>
 *
 *  <li>a {@code result} field, which can be either an array, a single value,
 *      or can be {@code null} if the request could not be successfully
 *      processed.</li>
 *
 *  <li>an {@code error} field, which must be {@code null} if the request was
 *      processed successfully, or must contain the last request error message
 *      if the request could not be successfully processed.</li>
 * </ul>
 *
 * <p>© 2013 - 2014 RedBottle Design, LLC.</p>
 * <p>© 2020 Inveniem.</p>
 *
 * @author Guy Paddock (guy@inveniem.com)
 */
public class ResponseMessage2
        extends ResponseMessage {
    /**
     * Constant for the name of the {@code method} field in the JSON object for this message.
     */
    protected static final String JSON_STRATUM_KEY_RESULT = "result";

    /**
     * Constant for the name of the {@code error} field in the JSON object for this message.
     */
    protected static final String JSON_STRATUM_KEY_ERROR = "error";

    /**
     * The result of the method call.
     */
    private Result result;

    /**
     * An error message describing why the last request could not be completed, if the request
     * failed.
     */
    private String error;

    /**
     * Constructor for {@link } that initializes a new instance from information in the
     * included JSON message.
     *
     * @param jsonMessage
     *   The JSON message object.
     *
     * @throws MalformedStratumMessageException
     *   If the provided JSON message object is not a properly-formed Stratum message or cannot be
     *   understood.
     */
    public ResponseMessage2(final JSONObject jsonMessage)
            throws MalformedStratumMessageException {
        super(jsonMessage);
    }

    /**
     * Constructor for {@link } that initializes a new instance having the specified ID
     * and result.
     *
     * @param id
     *   The unique identifier for the message. This may be {@code null}.
     * @param result
     *   The result of the method call.
     */
    public ResponseMessage2(final String id, final Result result) {
        super(id, result);

        this.setResult(result);
    }

    /**
     * Constructor for {@link } that initializes a new instance having the specified ID
     * and error.
     *
     * @param id
     *   The unique identifier for the message. This may be {@code null}.
     * @param error
     *   The error that occurred while processing the request.
     */
    public ResponseMessage2(final String id, final String error) {
        super(id, error);

        this.setError(error);
    }

    /**
     * Constructor for {@link } that initializes a new instance having the specified
     * ID, result, and error message (for graceful failures).
     *
     * @param id
     *   The unique identifier for the message. This may be {@code null}.
     * @param result
     *   The result of the method call.
     * @param error
     *   Additional error details about the graceful failure.
     */
    public ResponseMessage2(final String id, final Result result, final String error) {
        super(id, result, error);

        this.setResult(result);
        this.setError(error);
    }

    /**
     * Gets the result of the method call.
     *
     * @return The result of the method call.
     */
    public Result getResult() {
        return this.result;
    }

    /**
     * Gets an error message describing why the last request could not be completed, if the request
     * failed.
     *
     * @return The last error message.
     */
    public String getError() {
        return this.error;
    }

    /**
     * <p>Returns whether or not the request was successfully processed.</p>
     *
     * <p>This is equivalent to the following code:</p>
     *
     * <pre>
     * return (this.getError() == null);
     * </pre>
     *
     * @return {@code true} if the request was successfully processed; {@code false}, otherwise.
     */
    public boolean wasRequestSuccessful() {
        return (this.getError() == null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JSONObject toJson() {
        final JSONObject object = new JSONObject();

        try {
            final Result result = this.getResult();
            final String error  = this.getError();

            object.put(JSON_STRATUM_KEY_ID, (this.getId() != null) ? Integer.valueOf(this.getId()) : this.getId());
            object.put(JSON_STRATUM_KEY_ERROR,  (error != null)  ? error           : JSONObject.NULL);
            object.put(JSON_STRATUM_KEY_RESULT, (result != null) ? result.toJson() : JSONObject.NULL);
        } catch (JSONException ex) {
            // Should not happen
            throw new RuntimeException(
                    "Unexpected exception while contructing JSON object: " + ex.getMessage(),
                    ex
            );
        }

        return object;
    }

    /**
     * Sets the unique identifier for this response, which must correspond to the identifier provided
     * in the original request.
     *
     * @param id
     *   Sets the unique identifier for the message. This cannot be {@code null}.
     *
     * @throws IllegalArgumentException
     *   If {@code id} is {@code null}.
     */
    @Override
    protected void setId(final String id)
            throws IllegalArgumentException {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null.");
        }

        super.setId(id);
    }

    /**
     * Sets the result of the method call.
     *
     * @param result
     *   The new result of the method call.
     */
    protected void setResult(final Result result) {
        this.result = result;
    }

    /**
     * Sets an error message describing why the last request could not be completed, if the request
     * failed.
     *
     * @param error
     *   The error message.
     */
    protected void setError(final String error) {
        this.error = error;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void parseMessage(final JSONObject jsonMessage)
            throws MalformedStratumMessageException {
        this.parseResult(jsonMessage);
        this.parseError(jsonMessage);

        // Call superclass last, since it calls validateParsedData()
        super.parseMessage(jsonMessage);
    }

    /**
     * Parses-out the {@code result} field from the message.
     *
     * @param jsonMessage
     *   The message to parse.
     *
     * @throws MalformedStratumMessageException
     *   If the provided JSON message object is not a properly-formed Stratum message or cannot be
     *   understood.
     */
    protected void parseResult(final JSONObject jsonMessage)
            throws MalformedStratumMessageException {
        final Result result;

        if (!jsonMessage.has(JSON_STRATUM_KEY_RESULT)) {
            throw new MalformedStratumMessageException(
                    jsonMessage, String.format("missing '%s'", JSON_STRATUM_KEY_RESULT));
        }

        try {
            result = ResultFactory.getInstance().createResult(jsonMessage.get(JSON_STRATUM_KEY_RESULT));
        } catch (JSONException ex) {
            throw new MalformedStratumMessageException(jsonMessage, ex);
        }

        this.setResult(result);
    }

    /**
     * Parses-out the {@code error} field from the message.
     *
     * @param jsonMessage
     *   The message to parse.
     *
     * @throws MalformedStratumMessageException
     *   If the provided JSON message object is not a properly-formed Stratum message or cannot be
     *   understood.
     */
    protected void parseError(final JSONObject jsonMessage)
            throws MalformedStratumMessageException {
        String error = null;

        if (jsonMessage.has(JSON_STRATUM_KEY_ERROR) && !jsonMessage.isNull(JSON_STRATUM_KEY_ERROR)) {
            try {
                error = jsonMessage.get(JSON_STRATUM_KEY_ERROR).toString();
            } catch (JSONException ex) {
                throw new MalformedStratumMessageException(jsonMessage, ex);
            }
        }

        this.setError(error);
    }
}
