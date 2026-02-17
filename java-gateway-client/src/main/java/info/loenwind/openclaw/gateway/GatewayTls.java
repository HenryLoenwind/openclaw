package info.loenwind.openclaw.gateway;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class GatewayTls {
  private GatewayTls() {}

  public static SSLContext buildSslContext(GatewayTlsParams params, Consumer<String> onStore) {
    try {
      String expected = normalizeFingerprint(params.expectedFingerprint());
      X509TrustManager trust =
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
              if (chain == null || chain.length == 0) {
                throw new IllegalStateException("empty certificate chain");
              }
              final String fingerprint;
              try {
                fingerprint = sha256Hex(chain[0].getEncoded());
              } catch (Exception e) {
                throw new IllegalStateException("failed to read certificate fingerprint", e);
              }
              if (expected != null) {
                if (!expected.equals(fingerprint)) {
                  throw new IllegalStateException("gateway TLS fingerprint mismatch");
                }
                return;
              }
              if (params.allowTOFU() && onStore != null) {
                onStore.accept(fingerprint);
              }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {trust}, new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build TLS context", e);
    }
  }

  public static String probeFingerprint(String host, int port, int timeoutMs) {
    String trimmed = host == null ? "" : host.trim();
    if (trimmed.isEmpty() || port <= 0 || port > 65535) {
      return null;
    }
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {new TrustAll()}, new SecureRandom());
      SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
      socket.setSoTimeout(timeoutMs);
      socket.connect(new InetSocketAddress(trimmed, port), timeoutMs);
      if (trimmed.chars().anyMatch(Character::isLetter)) {
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setServerNames(java.util.List.of(new SNIHostName(trimmed)));
        socket.setSSLParameters(sslParameters);
      }
      socket.startHandshake();
      X509Certificate cert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
      socket.close();
      return sha256Hex(cert.getEncoded());
    } catch (Exception e) {
      return null;
    }
  }

  private static String sha256Hex(byte[] encoded) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
  }

  private static String normalizeFingerprint(String raw) {
    if (raw == null) {
      return null;
    }
    String stripped = raw.trim().replaceFirst("(?i)^sha-?256\\s*:?\\s*", "");
    String out = stripped.toLowerCase(Locale.US).replaceAll("[^0-9a-f]", "");
    return out.isBlank() ? null : out;
  }

  private static final class TrustAll implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
