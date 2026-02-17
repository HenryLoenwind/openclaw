package info.loenwind.openclaw;

public class PairingRequiredException extends RuntimeException {
  private final String requestId;

  public PairingRequiredException(String message, String requestId) {
    super(message);
    this.requestId = requestId;
  }

  public String getRequestId() {
    return requestId;
  }
}
