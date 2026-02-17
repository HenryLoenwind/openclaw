package info.loenwind.openclaw.gateway;

public record GatewayClientInfo(
    String id,
    String displayName,
    String version,
    String platform,
    String mode,
    String instanceId,
    String deviceFamily,
    String modelIdentifier) {}
