package stratum;

import com.redbottledesign.bitcoin.rpc.stratum.MalformedStratumMessageException;
import com.redbottledesign.bitcoin.rpc.stratum.message.ResultFactory;
import com.redbottledesign.bitcoin.rpc.stratum.transport.AbstractConnectionState;
import com.redbottledesign.bitcoin.rpc.stratum.transport.ConnectionState;
import com.redbottledesign.bitcoin.rpc.stratum.transport.StatefulMessageTransport;
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.StratumTcpServer;
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.StratumTcpServerConnection;
import org.ergoplatform.appkit.ErgoClient;
import org.ergoplatform.appkit.ErgoProver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stratum.counter.SubscriptionIdCounter;
import stratum.data.Options;
import stratum.message.Announcement;
import stratum.message.Requests;
import stratum.message.Response;

import org.bouncycastle.util.encoders.Hex;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Iterator;


public class ErgoStratumServer extends StratumTcpServer {

	private final Options options;
	private final SubscriptionIdCounter subscriptionIdCounter = new SubscriptionIdCounter();
	public final Pool pool;
    private final Logger logger = LoggerFactory.getLogger("ErgoStratumServer");
	public ErgoStratumServer(Options options) {
		this.options = options;
		pool = new Pool(options, this);
		try {
			pool.start();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

    public ErgoStratumServer(Options options, boolean thirdPartyScheduling,
                             boolean useCollateral, ErgoClient client, ErgoProver prover,
                             String apiKey, boolean reducedShareMessages) {
        this.options = options;
        pool = new Pool(options, this, useCollateral, client, prover, apiKey, reducedShareMessages);
        if(!thirdPartyScheduling){
            try {
                pool.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }else{
            try {
                pool.startThirdParty();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

	@Override
	protected StratumTcpServerConnection createConnection(Socket connectionSocket) {
		return new StratumTcpServerConnection(this, connectionSocket) {
			@Override
			protected ConnectionState createPostConnectState() {
				return new ErgoConnectionState(ErgoStratumServer.this, this, subscriptionIdCounter.next(), (InetSocketAddress) connectionSocket.getRemoteSocketAddress());
			}

            @Override
            public void close() {
                if (this.isOpen()) {
                    try {
                        this.getSocket().close();
                        this.getOutputThread().interrupt();
                        this.getInputThread().interrupt();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
            }
		};
	}
    @Override
    public void stopListening() {
        logger.info("Now stopping ErgoStratumServer");

            try {
                logger.info("Attempting to close sockets");
                if(this.getServerSocket() != null) {
                    this.getServerSocket().close();
                }
                Iterator<StratumTcpServerConnection> i = this.getConnections().asMap().values().iterator();
                while(i.hasNext()){
                    logger.info("Closing stratum connection");
                    i.next().close();
                }
            } catch (final IOException ex) {
                ex.printStackTrace();
            }

    }

	@Override
	protected void acceptConnection(StratumTcpServerConnection connection) {
		getConnections().put(connection.getConnectionId(), connection);
	}

	public void broadcastMiningJob(BlockTemplate blockTemplate) {
		getConnections().asMap().forEach((k, v) -> v.sendResponse(Announcement.miningJob(blockTemplate)));
	}

	public static class ErgoConnectionState extends AbstractConnectionState {

		private String extraNonce1;
        private final Logger logger = LoggerFactory.getLogger("ErgoConnectionState");
		public ErgoConnectionState(ErgoStratumServer server, StatefulMessageTransport transport, String subscriptionId, InetSocketAddress socketAddress) {
			super(transport);
			registerRequestHandler(Requests.Subscribe.NAME, Requests.Subscribe.class, m -> {
                logger.info("Got new subscription request: {}", m);
				try {
					extraNonce1 = server.pool.jobManager.extraNonceCounter.next();
					getTransport().sendResponse(Response.subscribe(m.getId(), subscriptionId, extraNonce1, 4));
					getTransport().sendResponse(Announcement.difficulty(new BigDecimal("1.0")));
					getTransport().sendResponse(Announcement.miningJob(server.pool.jobManager.currentJob));

				} catch (IOException e) {
					throw new RuntimeException(e);
				} catch (Exception e){
					logger.error("Connection error", e);
				}
			});
			registerRequestHandler(Requests.Authorize.NAME, Requests.Authorize.class, m -> {
                logger.info("Got new authorization request: workerName={}, password={}. Authorized.", m.workerName, m.password);
				try {
					getTransport().sendResponse(Response.authorize(m.getId(), true, null));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			registerRequestHandler(Requests.Submit.NAME, Requests.Submit.class, m -> {
				try {
					if (extraNonce1 == null) {
						getTransport().sendResponse(Response.submit(m.getId(), null, "25: not subscribed"));
						return;
					}

					String name = (String) m.getParams().get(0);
					String jobId = (String) m.getParams().get(1);
					byte[] extraNonce2 = Hex.decode((String) m.getParams().get(2));
					String nTime = (String) m.getParams().get(3);
					server.pool.jobManager.processShare(jobId, new BigInteger("1"), Hex.decode(extraNonce1), extraNonce2, nTime, socketAddress.getHostString(), socketAddress.getPort(), name);
					getTransport().sendResponse(Response.submit(m.getId(), ResultFactory.getInstance().createResult(Boolean.TRUE), null));
				} catch (IOException e) {
					throw new RuntimeException(e);
				} catch (JobManager.ProcessingException e) {
					try {

                        // We can accept potentially old shares here since the final judge of shares being "stale" is the TIME_FP
						if(e.getId() == 21) {
                            logger.warn("Received potentially old share from miner");
                            getTransport().sendResponse(Response.submit(m.getId(), ResultFactory.getInstance().createResult(Boolean.TRUE), null));
                            getTransport().sendResponse(Announcement.miningJob(server.pool.jobManager.currentJob));
                        }else{
                            logger.error("Share processing error", e);
                            getTransport().sendResponse(Response.submit(m.getId(), null, e.getId()+ ": " + e.getMessage()));
                        }
					} catch (IOException ex) {
						throw new RuntimeException(e);
					} catch (MalformedStratumMessageException ex) {
                        throw new RuntimeException(ex);
                    }
                } catch (MalformedStratumMessageException e) {
					logger.error("MalformedStratumMessage", e);
				}
			});
		}
	}
}
