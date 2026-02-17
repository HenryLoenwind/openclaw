package info.loenwind.openclaw.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class DeviceAuthStore {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Path tokensFile;

  public DeviceAuthStore(Path dataDir) {
    this.tokensFile = dataDir.resolve("auth/tokens.json");
  }

  public synchronized String loadToken(String deviceId, String role) {
    String key = tokenKey(deviceId, role);
    String token = readAll().get(key);
    if (token == null) {
      return null;
    }
    String trimmed = token.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public synchronized void saveToken(String deviceId, String role, String token) {
    Map<String, String> all = readAll();
    all.put(tokenKey(deviceId, role), token.trim());
    writeAll(all);
  }

  public synchronized void clearToken(String deviceId, String role) {
    Map<String, String> all = readAll();
    all.remove(tokenKey(deviceId, role));
    writeAll(all);
  }

  private String tokenKey(String deviceId, String role) {
    return "gateway.deviceToken."
        + deviceId.trim().toLowerCase(Locale.US)
        + "."
        + role.trim().toLowerCase(Locale.US);
  }

  private Map<String, String> readAll() {
    try {
      if (!Files.exists(tokensFile)) {
        return new HashMap<>();
      }
      return mapper.readValue(tokensFile.toFile(), new TypeReference<>() {});
    } catch (Exception e) {
      return new HashMap<>();
    }
  }

  private void writeAll(Map<String, String> all) {
    try {
      Files.createDirectories(tokensFile.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(tokensFile.toFile(), all);
    } catch (IOException ignored) {
    }
  }
}
