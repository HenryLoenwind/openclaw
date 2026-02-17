package info.loenwind.openclaw;

import info.loenwind.openclaw.gateway.DeviceAuthStore;
import info.loenwind.openclaw.gateway.DeviceIdentityStore;
import info.loenwind.openclaw.gateway.GatewayClientInfo;
import info.loenwind.openclaw.gateway.GatewayConnectOptions;
import info.loenwind.openclaw.gateway.GatewayEndpoint;
import info.loenwind.openclaw.gateway.GatewaySession;
import info.loenwind.openclaw.gateway.GatewayTlsParams;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ExampleMain {
  private ExampleMain() {}

  public static void main(String[] args) throws Exception {
    String gatewayUrl = System.getenv().getOrDefault("OPENCLAW_GATEWAY_URL", "ws://127.0.0.1:18789");
    String token = System.getenv("OPENCLAW_GATEWAY_TOKEN");
    String password = System.getenv("OPENCLAW_GATEWAY_PASSWORD");

    URI uri = URI.create(gatewayUrl);
    GatewayEndpoint endpoint =
        new GatewayEndpoint(
            "example|" + uri.getHost() + "|" + uri.getPort(),
            uri.getHost() + ":" + uri.getPort(),
            uri.getHost(),
            uri.getPort(),
            null,
            null,
            null,
            null,
            "wss".equalsIgnoreCase(uri.getScheme()),
            null);

    Path dataDir = Path.of(System.getProperty("user.home"), ".openclaw-java-gateway");
    DeviceIdentityStore identityStore = new DeviceIdentityStore(dataDir);
    DeviceAuthStore authStore = new DeviceAuthStore(dataDir);

    GatewayClientInfo clientInfo =
        new GatewayClientInfo(
            "java-example",
            "Java Example",
            "0.1.0",
            "java",
            "backend",
            null,
            null,
            null);

    GatewayConnectOptions options =
        new GatewayConnectOptions(
            "operator",
            List.of("operator.read"),
            List.of(),
            List.of(),
            Map.of(),
            clientInfo,
            "openclaw-java-gateway/0.1.0");

    GatewayTlsParams tls =
        "wss".equalsIgnoreCase(uri.getScheme())
            ? new GatewayTlsParams(true, null, true, endpoint.stableId())
            : null;

    GatewaySession session =
        new GatewaySession(
            identityStore,
            authStore,
            state ->
                System.out.printf(
                    "[connected] server=%s remote=%s mainSession=%s%n",
                    state.serverName(), state.remoteAddress(), state.mainSessionKey()),
            message -> System.out.println("[status] " + message),
            (event, payload) -> System.out.printf("[event] %s %s%n", event, payload),
            request -> GatewaySession.InvokeResult.error("UNAVAILABLE", "No invoke handler wired"),
            (stableId, fp) ->
                System.out.printf("[tls] %s fingerprint=%s%n", stableId, fp));

    session.connect(endpoint, token, password, options, tls);

    Thread.sleep(1_500);
    session.request("health", "{\"probe\":false}", 10_000)
        .thenAccept(health -> System.out.println("Health snapshot: " + health))
        .exceptionally(
            error -> {
              System.out.println("Health request failed: " + error.getMessage());
              return null;
            })
        .join();

    Thread.currentThread().join();
  }
}
