package info.loenwind.openclaw;

@FunctionalInterface
public interface ConnectionStatusListener {
  void onStatusChanged(ConnectionStatus status, String message, Throwable cause);
}
