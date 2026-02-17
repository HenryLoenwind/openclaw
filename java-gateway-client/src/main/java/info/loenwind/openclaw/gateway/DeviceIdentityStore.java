package info.loenwind.openclaw.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

public class DeviceIdentityStore {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Path identityFile;

  public DeviceIdentityStore(Path dataDir) {
    this.identityFile = dataDir.resolve("identity/device.json");
  }

  public synchronized DeviceIdentity loadOrCreate() {
    DeviceIdentity existing = load();
    if (existing != null) {
      String derived = deriveDeviceId(existing.publicKeyRawBase64());
      if (derived != null && !derived.equals(existing.deviceId())) {
        DeviceIdentity updated =
            new DeviceIdentity(
                derived,
                existing.publicKeyRawBase64(),
                existing.privateKeyPkcs8Base64(),
                existing.createdAtMs());
        save(updated);
        return updated;
      }
      return existing;
    }
    DeviceIdentity fresh = generate();
    save(fresh);
    return fresh;
  }

  public String signPayload(String payload, DeviceIdentity identity) {
    try {
      byte[] privateBytes = Base64.getDecoder().decode(identity.privateKeyPkcs8Base64());
      PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    } catch (Exception e) {
      return null;
    }
  }

  public String publicKeyBase64Url(DeviceIdentity identity) {
    try {
      byte[] raw = Base64.getDecoder().decode(identity.publicKeyRawBase64());
      return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    } catch (Exception ignored) {
      return null;
    }
  }

  private DeviceIdentity load() {
    try {
      if (!Files.exists(identityFile)) {
        return null;
      }
      DeviceIdentity decoded = mapper.readValue(identityFile.toFile(), DeviceIdentity.class);
      if (decoded.deviceId() == null
          || decoded.deviceId().isBlank()
          || decoded.publicKeyRawBase64() == null
          || decoded.publicKeyRawBase64().isBlank()
          || decoded.privateKeyPkcs8Base64() == null
          || decoded.privateKeyPkcs8Base64().isBlank()) {
        return null;
      }
      return decoded;
    } catch (Exception ignored) {
      return null;
    }
  }

  private void save(DeviceIdentity identity) {
    try {
      Files.createDirectories(identityFile.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(identityFile.toFile(), identity);
    } catch (IOException ignored) {
    }
  }

  private DeviceIdentity generate() {
    try {
      KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      byte[] spki = keyPair.getPublic().getEncoded();
      byte[] rawPublic = new byte[32];
      System.arraycopy(spki, spki.length - 32, rawPublic, 0, 32);
      String deviceId = sha256Hex(rawPublic);
      return new DeviceIdentity(
          deviceId,
          Base64.getEncoder().encodeToString(rawPublic),
          Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
          Instant.now().toEpochMilli());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate identity", e);
    }
  }

  private String deriveDeviceId(String publicKeyRawBase64) {
    try {
      return sha256Hex(Base64.getDecoder().decode(publicKeyRawBase64));
    } catch (Exception ignored) {
      return null;
    }
  }

  private String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
