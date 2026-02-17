package info.loenwind.openclaw;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class OpenClawGatewayClient {
  private static final int PROTOCOL_VERSION = 3;

	enum SCOPE {
		ADMIN, READ, WRITE, APPROVALS, PAIRING;

		@Override
		public String toString() {
			return "operator." + super.toString().toLowerCase(Locale.ENGLISH);
		}
	}

//  const APPROVAL_METHODS = new Set([
//    "exec.approval.request",
//    "exec.approval.waitDecision",
//    "exec.approval.resolve",
//  ]);
//  const NODE_ROLE_METHODS = new Set(["node.invoke.result", "node.event", "skills.bins"]);
//  const PAIRING_METHODS = new Set([
//    "node.pair.request",
//    "node.pair.list",
//    "node.pair.approve",
//    "node.pair.reject",
//    "node.pair.verify",
//    "device.pair.list",
//    "device.pair.approve",
//    "device.pair.reject",
//    "device.token.rotate",
//    "device.token.revoke",
//    "node.rename",
//  ]);
//  const ADMIN_METHOD_PREFIXES = ["exec.approvals."];
//  const READ_METHODS = new Set([
//    "health",
//    "logs.tail",
//    "channels.status",
//    "status",
//    "usage.status",
//    "usage.cost",
//    "tts.status",
//    "tts.providers",
//    "models.list",
//    "agents.list",
//    "agent.identity.get",
//    "skills.status",
//    "voicewake.get",
//    "sessions.list",
//    "sessions.preview",
//    "cron.list",
//    "cron.status",
//    "cron.runs",
//    "system-presence",
//    "last-heartbeat",
//    "node.list",
//    "node.describe",
//    "chat.history",
//    "config.get",
//    "talk.config",
//    "mesh.plan",
//    "mesh.status",
//  ]);
//  const WRITE_METHODS = new Set([
//    "send",
//    "agent",
//    "agent.wait",
//    "wake",
//    "talk.mode",
//    "tts.enable",
//    "tts.disable",
//    "tts.convert",
//    "tts.setProvider",
//    "voicewake.set",
//    "node.invoke",
//    "chat.send",
//    "chat.abort",
//    "browser.request",
//    "mesh.plan.auto",
//    "mesh.run",
//    "mesh.retry",
//  ]);

  private final URI gatewayUri;
  private final GatewayCredentials credentials;
  private final String clientId;
  private final String clientVersion;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
  private final AtomicReference<ConnectionStatusListener> statusListener =
      new AtomicReference<>((status, message, cause) -> {});

  private volatile WebSocket webSocket;
  private volatile boolean manualStop = false;
  private volatile boolean helloReceived = false;
  private volatile long tickIntervalMs = 30_000;
  private volatile long lastTickAtMs = 0;
  private volatile boolean watchdogStarted = false;

  public OpenClawGatewayClient(
      URI gatewayUri, GatewayCredentials credentials, String clientId, String clientVersion) {
    this.gatewayUri = gatewayUri;
    this.credentials = credentials;
    this.clientId = clientId;
    this.clientVersion = clientVersion;
  }

  public void setConnectionStatusListener(ConnectionStatusListener listener) {
    statusListener.set(listener == null ? (status, message, cause) -> {} : listener);
  }

  public CompletableFuture<Void> connect() {
    manualStop = false;
    publishStatus(ConnectionStatus.CONNECTING, "Opening websocket", null);
    return httpClient
        .newWebSocketBuilder()
        .buildAsync(gatewayUri, new GatewayWsListener())
        .thenAccept(ws -> this.webSocket = ws)
        .exceptionally(
            error -> {
              publishStatus(ConnectionStatus.RECONNECTING, "Connection failed, retrying", unwrap(error));
              scheduleReconnect();
              return null;
            });
  }

  public void disconnect() {
    manualStop = true;
    WebSocket ws = webSocket;
    if (ws != null) {
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
    }
    pendingRequests.forEach((id, future) -> future.completeExceptionally(new IllegalStateException("Disconnected")));
    pendingRequests.clear();
    publishStatus(ConnectionStatus.DISCONNECTED, "Disconnected", null);
  }

  public CompletableFuture<JsonNode> call(String method, JsonNode params) {
    WebSocket ws = webSocket;
    if (ws == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
    }

    String requestId = UUID.randomUUID().toString();
    ObjectNode request = mapper.createObjectNode();
    request.put("type", "req");
    request.put("id", requestId);
    request.put("method", method);
    request.set("params", params == null ? mapper.createObjectNode() : params);

    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pendingRequests.put(requestId, future);
    ws.sendText(request.toString(), true)
        .exceptionally(
            error -> {
              pendingRequests.remove(requestId);
              future.completeExceptionally(unwrap(error));
              return null;
            });
    return future;
  }

  public CompletableFuture<JsonNode> approvePairing(String requestId) {
    ObjectNode params = mapper.createObjectNode();
    params.put("requestId", requestId);
    return call("device.pair.approve", params);
  }

  public CompletableFuture<JsonNode> getHealth(boolean probe) {
    ObjectNode params = mapper.createObjectNode();
    params.put("probe", probe);
    return call("health", params);
  }

  private void sendConnectFrame() {
    ObjectNode connectParams = mapper.createObjectNode();
    connectParams.put("minProtocol", PROTOCOL_VERSION);
    connectParams.put("maxProtocol", PROTOCOL_VERSION);
    ObjectNode client = connectParams.putObject("client");
    client.put("id", clientId);
    client.put("displayName", "Java Client");
    client.put("version", clientVersion);
    client.put("platform", "java");
    client.put("mode", "backend");

    ObjectNode auth = connectParams.putObject("auth");
    if (credentials.token() != null) {
      auth.put("token", credentials.token());
    }
    if (credentials.password() != null) {
      auth.put("password", credentials.password());
    }

    connectParams.put("role", "operator");
	connectParams.putArray("scopes").add(SCOPE.READ.toString());

    call("connect", connectParams)
        .thenAccept(
            response -> {
              helloReceived = true;
              JsonNode policy = response.path("policy");
              if (policy.has("tickIntervalMs")) {
                tickIntervalMs = Math.max(1_000, policy.path("tickIntervalMs").asLong(30_000));
              }
              lastTickAtMs = System.currentTimeMillis();
              publishStatus(ConnectionStatus.CONNECTED, "Connected", null);
            })
        .exceptionally(
            error -> {
              Throwable actual = unwrap(error);
              publishStatus(ConnectionStatus.RECONNECTING, "Connect handshake failed, retrying", actual);
              WebSocket ws = webSocket;
						webSocket = null;
              if (ws != null) {
                ws.abort();
              }
              return null;
            });
  }

  private void scheduleReconnect() {
    if (manualStop) {
      return;
    }
    scheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
  }

  private void startKeepAliveWatchdog() {
    if (watchdogStarted) {
      return;
    }
    watchdogStarted = true;
    scheduler.scheduleAtFixedRate(
        () -> {
          if (manualStop || !helloReceived) {
            return;
          }
          long ageMs = System.currentTimeMillis() - lastTickAtMs;
          if (ageMs > (tickIntervalMs * 2)) {
            publishStatus(ConnectionStatus.RECONNECTING, "Tick timeout, reconnecting", null);
            WebSocket ws = webSocket;
            if (ws != null) {
              ws.abort();
            }
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
  }

  private void publishStatus(ConnectionStatus status, String message, Throwable cause) {
    statusListener.get().onStatusChanged(status, message, cause);
  }

  private Throwable unwrap(Throwable error) {
    return error.getCause() == null ? error : error.getCause();
  }

  private final class GatewayWsListener implements WebSocket.Listener {
    private final StringBuilder textBuffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      WebSocket.Listener.super.onOpen(webSocket);
		OpenClawGatewayClient.this.webSocket = webSocket;
      helloReceived = false;
      startKeepAliveWatchdog();
      sendConnectFrame();
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      textBuffer.append(data);
      if (!last) {
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
      }

      String payload = textBuffer.toString();
      textBuffer.setLength(0);
      try {
        JsonNode frame = mapper.readTree(payload);
        handleFrame(frame);
      } catch (Exception parseError) {
        publishStatus(ConnectionStatus.RECONNECTING, "Invalid frame received", parseError);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      helloReceived = false;
      pendingRequests.forEach((id, future) -> future.completeExceptionally(new IllegalStateException("Socket closed")));
      pendingRequests.clear();
      if (!manualStop) {
        publishStatus(ConnectionStatus.RECONNECTING, "Socket closed: " + reason, null);
        scheduleReconnect();
      } else {
        publishStatus(ConnectionStatus.DISCONNECTED, "Socket closed", null);
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      publishStatus(ConnectionStatus.RECONNECTING, "WebSocket error", error);
    }

    private void handleFrame(JsonNode frame) {
      String type = frame.path("type").asText("");
      if ("evt".equals(type)) {
        String event = frame.path("event").asText("");
        if ("tick".equals(event)) {
          lastTickAtMs = System.currentTimeMillis();
        } else if ("connect.challenge".equals(event)) {
          sendConnectFrame();
        }
        return;
      }

      if (!"res".equals(type)) {
        return;
      }

      String id = frame.path("id").asText();
      CompletableFuture<JsonNode> pending = pendingRequests.remove(id);
      if (pending == null) {
        return;
      }

      if (frame.path("ok").asBoolean(false)) {
        pending.complete(frame.path("payload"));
        return;
      }

      String message = frame.path("error").path("message").asText("Gateway request failed");
      JsonNode details = frame.path("error").path("details");
      if ("pairing required".equalsIgnoreCase(message) && details.has("requestId")) {
        String requestId = details.path("requestId").asText();
        publishStatus(ConnectionStatus.PAIRING_REQUIRED, "Pairing required: " + requestId, null);
        pending.completeExceptionally(new PairingRequiredException(message, requestId));
      } else {
        pending.completeExceptionally(new IllegalStateException(message));
      }
    }
  }
}
