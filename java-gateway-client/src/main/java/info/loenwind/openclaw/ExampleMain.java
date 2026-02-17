package info.loenwind.openclaw;

import java.net.URI;

public final class ExampleMain {
  private ExampleMain() {}

  public static void main(String[] args) throws Exception {
    String gatewayUrl = System.getenv().getOrDefault("OPENCLAW_GATEWAY_URL", "ws://127.0.0.1:18789");
    String token = System.getenv("OPENCLAW_GATEWAY_TOKEN");
    String password = System.getenv("OPENCLAW_GATEWAY_PASSWORD");

    GatewayCredentials credentials = new GatewayCredentials(token, password);
    OpenClawGatewayClient client =
        new OpenClawGatewayClient(URI.create(gatewayUrl), credentials, "java-app", "0.1.0");

    client.setConnectionStatusListener(
        (status, message, cause) -> {
          System.out.printf("[status] %s - %s%n", status, message);
          if (cause != null) {
            cause.printStackTrace(System.out);
          }
        });

    client.connect().join();

    // Example API call.
    client.getHealth(false).thenAccept(health -> System.out.println("Health snapshot: " + health)).join();

    // Keep the demo process alive so reconnect behavior can be observed.
    Thread.currentThread().join();
  }
}
