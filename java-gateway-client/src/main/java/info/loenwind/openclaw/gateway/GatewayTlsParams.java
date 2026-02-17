package info.loenwind.openclaw.gateway;

public record GatewayTlsParams(
    boolean required, String expectedFingerprint, boolean allowTOFU, String stableId) {}
