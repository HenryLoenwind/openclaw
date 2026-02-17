package info.loenwind.openclaw.gateway;

import java.util.List;
import java.util.Map;

public record GatewayConnectOptions(
    String role,
    List<String> scopes,
    List<String> caps,
    List<String> commands,
    Map<String, Boolean> permissions,
    GatewayClientInfo client,
    String userAgent) {

  public GatewayConnectOptions {
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
    caps = caps == null ? List.of() : List.copyOf(caps);
    commands = commands == null ? List.of() : List.copyOf(commands);
    permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
  }
}
