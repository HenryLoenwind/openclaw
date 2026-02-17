package info.loenwind.openclaw.gateway;

public record DeviceIdentity(
    String deviceId, String publicKeyRawBase64, String privateKeyPkcs8Base64, long createdAtMs) {}
