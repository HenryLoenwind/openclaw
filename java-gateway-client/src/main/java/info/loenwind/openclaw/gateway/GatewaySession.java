package info.loenwind.openclaw.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;

public class GatewaySession {
  public record InvokeRequest(String id, String nodeId, String command, String paramsJson, Long timeoutMs) {}

  public record ErrorShape(String code, String message) {}

  public record InvokeResult(boolean ok, String payloadJson, ErrorShape error) {
    public static InvokeResult ok(String payloadJson) {
      return new InvokeResult(true, payloadJson, null);
    }

    public static InvokeResult error(String code, String message) {
      return new InvokeResult(false, null, new ErrorShape(code, message));
    }
  }

  @FunctionalInterface
  public interface InvokeHandler {
    InvokeResult handle(InvokeRequest request) throws Exception;
  }

  private final ObjectMapper mapper = new ObjectMapper();
  private final DeviceIdentityStore identityStore;
  private final DeviceAuthStore deviceAuthStore;
  private final Consumer<ConnectionState> onConnected;
  private final Consumer<String> onDisconnected;
  private final BiConsumer<String, String> onEvent;
  private final InvokeHandler onInvoke;
  private final BiConsumer<String, String> onTlsFingerprint;
  private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final Map<String, CompletableFuture<RpcResponse>> pending = new ConcurrentHashMap<>();

  private volatile DesiredConnection desired;
  private volatile Connection connection;
  private volatile String canvasHostUrl;
  private volatile String mainSessionKey;

  public record ConnectionState(String serverName, String remoteAddress, String mainSessionKey) {}

  public GatewaySession(
      DeviceIdentityStore identityStore,
      DeviceAuthStore deviceAuthStore,
      Consumer<ConnectionState> onConnected,
      Consumer<String> onDisconnected,
      BiConsumer<String, String> onEvent,
      InvokeHandler onInvoke,
      BiConsumer<String, String> onTlsFingerprint) {
    this.identityStore = identityStore;
    this.deviceAuthStore = deviceAuthStore;
    this.onConnected = onConnected;
    this.onDisconnected = onDisconnected;
    this.onEvent = onEvent;
    this.onInvoke = onInvoke;
    this.onTlsFingerprint = onTlsFingerprint;
    scheduler.execute(this::runLoop);
  }

  public void connect(
      GatewayEndpoint endpoint,
      String token,
      String password,
      GatewayConnectOptions options,
      GatewayTlsParams tls) {
    desired = new DesiredConnection(endpoint, token, password, options, tls);
    Connection current = connection;
    if (current != null) {
      current.closeQuietly();
    }
  }

  public void reconnect() {
    Connection current = connection;
    if (current != null) {
      current.closeQuietly();
    }
  }

  public void disconnect() {
    desired = null;
    Connection current = connection;
    if (current != null) {
      current.closeQuietly();
    }
    onDisconnected.accept("Offline");
  }

  public CompletableFuture<String> request(String method, String paramsJson, long timeoutMs) {
    Connection current = connection;
    if (current == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
    }
    JsonNode params = parseJsonOrNull(paramsJson);
    return current
        .request(method, params, timeoutMs)
        .thenApply(
            response -> {
              if (response.ok()) {
                return response.payloadJson() == null ? "" : response.payloadJson();
              }
              ErrorShape err = response.error();
              throw new IllegalStateException(
                  (err == null ? "UNAVAILABLE" : err.code())
                      + ": "
                      + (err == null ? "request failed" : err.message()));
            });
  }

  public CompletableFuture<Void> sendNodeEvent(String event, String payloadJson) {
    Connection current = connection;
    if (current == null) {
      return CompletableFuture.completedFuture(null);
    }
    ObjectNode params = mapper.createObjectNode();
    params.put("event", event);
    JsonNode parsedPayload = parseJsonOrNull(payloadJson);
    if (parsedPayload != null) {
      params.set("payload", parsedPayload);
    } else if (payloadJson != null) {
      params.put("payloadJSON", payloadJson);
    } else {
      params.set("payloadJSON", NullNode.getInstance());
    }
    return current.request("node.event", params, 8_000).thenApply(x -> null);
  }

  public String currentCanvasHostUrl() {
    return canvasHostUrl;
  }

  public String currentMainSessionKey() {
    return mainSessionKey;
  }

  private void runLoop() {
    int attempt = 0;
    while (!ioExecutor.isShutdown()) {
      DesiredConnection target = desired;
      if (target == null) {
        sleep(250);
        continue;
      }
      onDisconnected.accept(attempt == 0 ? "Connecting…" : "Reconnecting…");
      Connection next = new Connection(target);
      connection = next;
      try {
        next.connect().join();
        attempt = 0;
        next.awaitClose().join();
      } catch (Exception e) {
        attempt += 1;
        onDisconnected.accept("Gateway error: " + rootMessage(e));
        long sleepMs = Math.min(8_000L, (long) (350.0 * Math.pow(1.7, attempt)));
        sleep(sleepMs);
      } finally {
        connection = null;
        canvasHostUrl = null;
        mainSessionKey = null;
      }
    }
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private String rootMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }

  private final class Connection {
    private final DesiredConnection target;
    private final HttpClient client;
    private final CompletableFuture<Void> opened = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final CompletableFuture<String> connectNonce = new CompletableFuture<>();
    private volatile WebSocket socket;

    private Connection(DesiredConnection target) {
      this.target = target;
      HttpClient.Builder clientBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
      if (target.tls() != null) {
        SSLContext sslContext =
            GatewayTls.buildSslContext(
                target.tls(),
                fp -> {
                  if (onTlsFingerprint != null) {
                    onTlsFingerprint.accept(target.tls().stableId(), fp);
                  }
                });
        clientBuilder.sslContext(sslContext);
      }
      this.client = clientBuilder.build();
    }

    public CompletableFuture<Void> connect() {
      String scheme = target.tls() != null ? "wss" : "ws";
      URI uri = URI.create(scheme + "://" + target.endpoint().host() + ":" + target.endpoint().port());
      return client
          .newWebSocketBuilder()
          .header("Origin", (target.tls() != null ? "https" : "http") + "://" + target.endpoint().host() + ":" + target.endpoint().port())
          .buildAsync(uri, new Listener())
          .thenAccept(ws -> socket = ws)
          .thenCompose(v -> opened);
    }

    public CompletableFuture<RpcResponse> request(String method, JsonNode params, long timeoutMs) {
      String id = UUID.randomUUID().toString();
      CompletableFuture<RpcResponse> future = new CompletableFuture<>();
      pending.put(id, future);

      ObjectNode frame = mapper.createObjectNode();
      frame.put("type", "req");
      frame.put("id", id);
      frame.put("method", method);
      if (params != null) {
        frame.set("params", params);
      }
      WebSocket ws = socket;
      if (ws == null) {
        pending.remove(id);
        return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
      }
      ws.sendText(frame.toString(), true)
          .exceptionally(
              error -> {
                pending.remove(id);
                future.completeExceptionally(error);
                return null;
              });

      scheduler.schedule(
          () -> {
            CompletableFuture<RpcResponse> waiter = pending.remove(id);
            if (waiter != null) {
              waiter.completeExceptionally(new IllegalStateException("request timeout"));
            }
          },
          timeoutMs,
          TimeUnit.MILLISECONDS);
      return future;
    }

    public CompletableFuture<Void> awaitClose() {
      return closed;
    }

    public void closeQuietly() {
      WebSocket ws = socket;
      if (ws != null) {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      }
      closed.complete(null);
    }

    private void sendConnect(String nonce) {
      DeviceIdentity identity = identityStore.loadOrCreate();
      String storedToken = deviceAuthStore.loadToken(identity.deviceId(), target.options().role());
      String trimmedToken = target.token() == null ? "" : target.token().trim();
      String authToken = (storedToken == null || storedToken.isBlank()) ? trimmedToken : storedToken;
      boolean canFallbackToShared = storedToken != null && !storedToken.isBlank() && !trimmedToken.isBlank();

      ObjectNode params = buildConnectParams(identity, nonce, authToken, target.password());
      request("connect", params, 8_000)
          .thenAccept(
              response -> {
                if (!response.ok()) {
                  if (canFallbackToShared) {
                    deviceAuthStore.clearToken(identity.deviceId(), target.options().role());
                  }
                  opened.completeExceptionally(new IllegalStateException("connect failed"));
                  return;
                }
                try {
                  JsonNode payload = mapper.readTree(response.payloadJson());
                  JsonNode auth = payload.path("auth");
                  String deviceToken = text(auth, "deviceToken");
                  String authRole = text(auth, "role");
                  if (deviceToken != null && !deviceToken.isBlank()) {
                    deviceAuthStore.saveToken(identity.deviceId(), authRole == null ? target.options().role() : authRole, deviceToken);
                  }
                  canvasHostUrl = normalizeCanvasHostUrl(text(payload, "canvasHostUrl"), target.endpoint());
                  mainSessionKey = text(payload.path("snapshot").path("sessionDefaults"), "mainSessionKey");
                  if (onConnected != null) {
                    onConnected.accept(new ConnectionState(text(payload.path("server"), "host"), remoteAddress(target.endpoint()), mainSessionKey));
                  }
                  opened.complete(null);
                } catch (Exception e) {
                  opened.completeExceptionally(e);
                }
              })
          .exceptionally(
              error -> {
                opened.completeExceptionally(error);
                closeQuietly();
                return null;
              });
    }

    private ObjectNode buildConnectParams(DeviceIdentity identity, String nonce, String authToken, String authPassword) {
      GatewayClientInfo clientInfo = target.options().client();
      ObjectNode params = mapper.createObjectNode();
      params.put("minProtocol", GatewayProtocol.VERSION);
      params.put("maxProtocol", GatewayProtocol.VERSION);

      ObjectNode client = params.putObject("client");
      client.put("id", clientInfo.id());
      putIfNotBlank(client, "displayName", clientInfo.displayName());
      client.put("version", clientInfo.version());
      client.put("platform", clientInfo.platform());
      client.put("mode", clientInfo.mode());
      putIfNotBlank(client, "instanceId", clientInfo.instanceId());
      putIfNotBlank(client, "deviceFamily", clientInfo.deviceFamily());
      putIfNotBlank(client, "modelIdentifier", clientInfo.modelIdentifier());

      if (!target.options().caps().isEmpty()) {
        ArrayNode caps = params.putArray("caps");
        target.options().caps().forEach(caps::add);
      }
      if (!target.options().commands().isEmpty()) {
        ArrayNode commands = params.putArray("commands");
        target.options().commands().forEach(commands::add);
      }
      if (!target.options().permissions().isEmpty()) {
        ObjectNode permissions = params.putObject("permissions");
        target.options().permissions().forEach(permissions::put);
      }
      params.put("role", target.options().role());
      if (!target.options().scopes().isEmpty()) {
        ArrayNode scopes = params.putArray("scopes");
        target.options().scopes().forEach(scopes::add);
      }

      String trimmedPassword = authPassword == null ? "" : authPassword.trim();
      if (authToken != null && !authToken.isBlank()) {
        params.putObject("auth").put("token", authToken);
      } else if (!trimmedPassword.isBlank()) {
        params.putObject("auth").put("password", trimmedPassword);
      }

      long signedAtMs = System.currentTimeMillis();
      String payload =
          buildDeviceAuthPayload(
              identity.deviceId(),
              clientInfo.id(),
              clientInfo.mode(),
              target.options().role(),
              target.options().scopes(),
              signedAtMs,
              authToken,
              nonce);
      String signature = identityStore.signPayload(payload, identity);
      String publicKey = identityStore.publicKeyBase64Url(identity);
      if (signature != null && publicKey != null) {
        ObjectNode device = params.putObject("device");
        device.put("id", identity.deviceId());
        device.put("publicKey", publicKey);
        device.put("signature", signature);
        device.put("signedAt", signedAtMs);
        putIfNotBlank(device, "nonce", nonce);
      }

      params.put("locale", Locale.getDefault().toLanguageTag());
      putIfNotBlank(params, "userAgent", target.options().userAgent());
      return params;
    }

    private final class Listener implements WebSocket.Listener {
      private final StringBuilder textBuffer = new StringBuilder();

      @Override
      public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        if (isLoopbackHost(target.endpoint().host())) {
          sendConnect(null);
        } else {
          connectNonce.orTimeout(2, TimeUnit.SECONDS)
              .exceptionally(err -> null)
              .thenAccept(Connection.this::sendConnect);
        }
        webSocket.request(1);
      }

      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
          handleMessage(textBuffer.toString());
          textBuffer.setLength(0);
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        failPending("Gateway closed: " + reason);
        closed.complete(null);
        onDisconnected.accept("Gateway closed: " + reason);
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void onError(WebSocket webSocket, Throwable error) {
        failPending("Gateway error: " + rootMessage(error));
        closed.complete(null);
        onDisconnected.accept("Gateway error: " + rootMessage(error));
      }
    }

    private void handleMessage(String text) {
      try {
        JsonNode frame = mapper.readTree(text);
        String type = text(frame, "type");
        if ("res".equals(type)) {
          String id = text(frame, "id");
          if (id == null) {
            return;
          }
          RpcResponse response =
              new RpcResponse(
                  id,
                  frame.path("ok").asBoolean(false),
                  frame.has("payload") ? frame.path("payload").toString() : null,
                  frame.has("error")
                      ? new ErrorShape(text(frame.path("error"), "code"), text(frame.path("error"), "message"))
                      : null);
          CompletableFuture<RpcResponse> waiter = pending.remove(id);
          if (waiter != null) {
            waiter.complete(response);
          }
          return;
        }

        if (!"event".equals(type)) {
          return;
        }

        String event = text(frame, "event");
        String payloadJson = frame.has("payload") ? frame.path("payload").toString() : text(frame, "payloadJSON");
        if ("connect.challenge".equals(event)) {
          connectNonce.complete(extractConnectNonce(payloadJson));
          return;
        }
        if ("node.invoke.request".equals(event) && payloadJson != null && onInvoke != null) {
          handleInvokeEvent(payloadJson);
          return;
        }
        onEvent.accept(event, payloadJson);
      } catch (Exception ignored) {
      }
    }

    private void handleInvokeEvent(String payloadJson) {
      ioExecutor.execute(
          () -> {
            try {
              JsonNode payload = mapper.readTree(payloadJson);
              InvokeRequest request =
                  new InvokeRequest(
                      text(payload, "id"),
                      text(payload, "nodeId"),
                      text(payload, "command"),
                      text(payload, "paramsJSON") != null
                          ? text(payload, "paramsJSON")
                          : (payload.has("params") && !payload.path("params").isNull() ? payload.path("params").toString() : null),
                      payload.has("timeoutMs") ? payload.path("timeoutMs").asLong() : null);
              InvokeResult result = onInvoke.handle(request);
              sendInvokeResult(request.id(), request.nodeId(), result);
            } catch (Exception error) {
              String message = error.getMessage() == null ? "invoke failed" : error.getMessage();
              sendInvokeResult(null, null, InvokeResult.error("UNAVAILABLE", message));
            }
          });
    }

    private void sendInvokeResult(String id, String nodeId, InvokeResult result) {
      if (id == null || nodeId == null) {
        return;
      }
      ObjectNode params = mapper.createObjectNode();
      params.put("id", id);
      params.put("nodeId", nodeId);
      params.put("ok", result.ok());
      JsonNode parsed = parseJsonOrNull(result.payloadJson());
      if (parsed != null) {
        params.set("payload", parsed);
      } else if (result.payloadJson() != null) {
        params.put("payloadJSON", result.payloadJson());
      }
      if (result.error() != null) {
        ObjectNode error = params.putObject("error");
        error.put("code", result.error().code());
        error.put("message", result.error().message());
      }
      request("node.invoke.result", params, 15_000);
    }

    private String extractConnectNonce(String payloadJson) {
      JsonNode payload = parseJsonOrNull(payloadJson);
      return payload == null ? null : text(payload, "nonce");
    }

    private void failPending(String message) {
      pending.forEach((id, future) -> future.completeExceptionally(new IllegalStateException(message)));
      pending.clear();
    }
  }

  private record DesiredConnection(
      GatewayEndpoint endpoint,
      String token,
      String password,
      GatewayConnectOptions options,
      GatewayTlsParams tls) {}

  private record RpcResponse(String id, boolean ok, String payloadJson, ErrorShape error) {}

  private JsonNode parseJsonOrNull(String text) {
    try {
      if (text == null || text.isBlank()) {
        return null;
      }
      return mapper.readTree(text);
    } catch (Exception e) {
      return null;
    }
  }

  private static String buildDeviceAuthPayload(
      String deviceId,
      String clientId,
      String clientMode,
      String role,
      java.util.List<String> scopes,
      long signedAtMs,
      String token,
      String nonce) {
    String version = nonce == null || nonce.isBlank() ? "v1" : "v2";
    String joinedScopes = String.join(",", scopes);
    String joined =
        String.join(
            "|",
            version,
            deviceId,
            clientId,
            clientMode,
            role,
            joinedScopes,
            Long.toString(signedAtMs),
            token == null ? "" : token);
    return nonce == null || nonce.isBlank() ? joined : joined + "|" + nonce;
  }

  private static String normalizeCanvasHostUrl(String raw, GatewayEndpoint endpoint) {
    String trimmed = raw == null ? "" : raw.trim();
    URI parsed = null;
    try {
      if (!trimmed.isBlank()) {
        parsed = URI.create(trimmed);
      }
    } catch (Exception ignored) {
    }
    String host = parsed == null || parsed.getHost() == null ? "" : parsed.getHost().trim();
    int port = parsed == null ? -1 : parsed.getPort();
    String scheme = parsed == null || parsed.getScheme() == null || parsed.getScheme().isBlank() ? "http" : parsed.getScheme().trim();
    boolean tls = endpoint.port() == 443 || endpoint.host().contains(".");

    if (!trimmed.isBlank() && !isLoopbackHost(host)) {
      if (tls && port > 0 && port != 443) {
        String formatted = host.contains(":") ? "[" + host + "]" : host;
        return "https://" + formatted;
      }
      return trimmed;
    }

    String fallbackHost =
        notBlank(endpoint.tailnetDns()) != null
            ? endpoint.tailnetDns().trim()
            : notBlank(endpoint.lanHost()) != null ? endpoint.lanHost().trim() : endpoint.host().trim();
    if (fallbackHost.isEmpty()) {
      return trimmed.isBlank() ? null : trimmed;
    }
    String fallbackScheme = tls ? "https" : scheme;
    int fallbackPort = tls ? endpoint.port() : (endpoint.canvasPort() == null ? endpoint.port() : endpoint.canvasPort());
    String formattedHost = fallbackHost.contains(":") ? "[" + fallbackHost + "]" : fallbackHost;
    String portSuffix =
        ("https".equals(fallbackScheme) && fallbackPort == 443)
                || ("http".equals(fallbackScheme) && fallbackPort == 80)
            ? ""
            : ":" + fallbackPort;
    return fallbackScheme + "://" + formattedHost + portSuffix;
  }

  private static String remoteAddress(GatewayEndpoint endpoint) {
    return endpoint.host().contains(":")
        ? "[" + endpoint.host() + "]:" + endpoint.port()
        : endpoint.host() + ":" + endpoint.port();
  }

  private static String text(JsonNode node, String key) {
    if (node == null || node.isMissingNode() || !node.has(key) || node.path(key).isNull()) {
      return null;
    }
    return node.path(key).asText();
  }

  private static void putIfNotBlank(ObjectNode node, String key, String value) {
    if (value != null && !value.isBlank()) {
      node.put(key, value);
    }
  }

  private static String notBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static boolean isLoopbackHost(String raw) {
    if (raw == null) {
      return false;
    }
    String host = raw.trim().toLowerCase(Locale.US);
    if (host.isEmpty()) {
      return false;
    }
    return host.equals("localhost")
        || host.equals("::1")
        || host.equals("0.0.0.0")
        || host.equals("::")
        || host.startsWith("127.");
  }
}
