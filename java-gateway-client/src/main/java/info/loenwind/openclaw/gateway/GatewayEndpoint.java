package info.loenwind.openclaw.gateway;

import java.util.Locale;

public record GatewayEndpoint(
    String stableId,
    String name,
    String host,
    int port,
    String lanHost,
    String tailnetDns,
    Integer gatewayPort,
    Integer canvasPort,
    boolean tlsEnabled,
    String tlsFingerprintSha256) {

  public static GatewayEndpoint manual(String host, int port) {
    return new GatewayEndpoint(
        "manual|" + host.toLowerCase(Locale.US) + "|" + port,
        host + ":" + port,
        host,
        port,
        null,
        null,
        null,
        null,
        false,
        null);
  }
}
