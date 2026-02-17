package info.loenwind.openclaw;

import java.util.Objects;

public record GatewayCredentials(String token, String password) {
  public GatewayCredentials {
    if ((token == null || token.isBlank()) && (password == null || password.isBlank())) {
      throw new IllegalArgumentException("Either token or password must be set.");
    }
    if (token != null && token.isBlank()) {
      token = null;
    }
    if (password != null && password.isBlank()) {
      password = null;
    }
  }

  public static GatewayCredentials withToken(String token) {
    return new GatewayCredentials(Objects.requireNonNull(token, "token"), null);
  }

  public static GatewayCredentials withPassword(String password) {
    return new GatewayCredentials(null, Objects.requireNonNull(password, "password"));
  }
}
