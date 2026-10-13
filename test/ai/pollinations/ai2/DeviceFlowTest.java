package ai.pollinations.ai2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ai.pollinations.ai2.PollinationsApi.ApiError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs the real HTTP client against a local fake of enter.pollinations.ai.
 *
 * <p>The fake is a minimal HTTP/1.1 responder on a plain ServerSocket. The intended fake was
 * com.sun.net.httpserver, but in the sandbox used for this build HttpServer.create() fails
 * inside the JDK selector (AF_UNIX loopback "Invalid argument: connect"), so it cannot start.
 */
public class DeviceFlowTest {

  /** Answers one request: {status, json body}. */
  interface Handler {
    String[] respond(String path, String body);
  }

  private FakeServer server;
  private String base;
  private final List<String> tokenRequestBodies = new ArrayList<String>();
  /** Scripted answers for /api/device/token, consumed in order. The last one repeats. */
  private final List<String[]> tokenScript = new ArrayList<String[]>();
  private String[] codeAnswer;

  @Before
  public void startFakeServer() throws IOException {
    codeAnswer =
        new String[] {
          "200",
          "{\"device_code\":\"dev-123\",\"user_code\":\"ABCD-1234\","
              + "\"verification_uri\":\"https://enter.pollinations.ai/device\","
              + "\"verification_uri_complete\":\"https://enter.pollinations.ai/device?user_code=ABCD-1234\","
              + "\"expires_in\":1800,\"interval\":5}"
        };
    server =
        new FakeServer(
            new Handler() {
              public String[] respond(String path, String body) {
                if ("/api/device/code".equals(path)) {
                  return codeAnswer;
                }
                if ("/api/device/token".equals(path)) {
                  tokenRequestBodies.add(body);
                  return tokenScript.size() > 1 ? tokenScript.remove(0) : tokenScript.get(0);
                }
                return new String[] {"404", "{\"error\":\"not found\"}"};
              }
            });
    base = "http://127.0.0.1:" + server.port();
  }

  @After
  public void stopFakeServer() {
    server.close();
  }

  @Test
  public void requestCodeParsesDeviceCodeAndTimings() throws Exception {
    DeviceFlow.Code code = DeviceFlow.requestCode(base);
    assertEquals("dev-123", code.deviceCode);
    assertEquals("ABCD-1234", code.userCode);
    assertEquals("https://enter.pollinations.ai/device?user_code=ABCD-1234", code.verificationUrlComplete);
    assertEquals(5000L, code.intervalMs);
    assertEquals(1800000L, code.expiresMs);
  }

  @Test
  public void pendingAndSlowDownThenApprovalReturnsKeyWithBackedOffInterval() throws Exception {
    tokenScript.add(new String[] {"400", "{\"error\":\"authorization_pending\"}"});
    tokenScript.add(new String[] {"400", "{\"error\":\"authorization_pending\"}"});
    tokenScript.add(new String[] {"400", "{\"error\":\"slow_down\"}"});
    tokenScript.add(new String[] {"200", "{\"access_token\":\"sk_test_value\",\"token_type\":\"bearer\"}"});
    FakeControl ctl = new FakeControl(1000000L);
    String key = DeviceFlow.awaitKey(base, DeviceFlow.requestCode(base), ctl);
    assertEquals("sk_test_value", key);
    // First poll waits one interval; slow_down adds 5 s before the next wait.
    assertEquals(Arrays.asList(5000L, 5000L, 5000L, 10000L), ctl.sleeps);
    assertEquals(4, tokenRequestBodies.size());
    assertTrue(tokenRequestBodies.get(0).contains("\"device_code\":\"dev-123\""));
  }

  @Test
  public void expiredTokenStopsWithAFriendlyError() throws Exception {
    tokenScript.add(new String[] {"400", "{\"error\":\"expired_token\"}"});
    try {
      DeviceFlow.awaitKey(base, DeviceFlow.requestCode(base), new FakeControl(1000000L));
      fail("expected ApiError");
    } catch (ApiError e) {
      assertTrue(e.getMessage(), e.getMessage().contains("expired"));
    }
  }

  @Test
  public void unknownDeviceCodeFromServerIsReported() throws Exception {
    tokenScript.add(
        new String[] {"400", "{\"error\":\"invalid_grant\",\"error_description\":\"Unknown device code\"}"});
    try {
      DeviceFlow.awaitKey(base, DeviceFlow.requestCode(base), new FakeControl(1000000L));
      fail("expected ApiError");
    } catch (ApiError e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not recognised"));
    }
  }

  @Test
  public void localDeadlineStopsPollingWhenCodeExpires() throws Exception {
    codeAnswer[1] = codeAnswer[1].replace("\"expires_in\":1800", "\"expires_in\":12");
    tokenScript.add(new String[] {"400", "{\"error\":\"authorization_pending\"}"});
    FakeControl ctl = new FakeControl(1000000L);
    try {
      DeviceFlow.awaitKey(base, DeviceFlow.requestCode(base), ctl);
      fail("expected ApiError");
    } catch (ApiError e) {
      assertTrue(e.getMessage(), e.getMessage().contains("expired"));
    }
    // Polls after the 5 s and 10 s waits; the wait to 15 s is past the 12 s deadline, so no third poll.
    assertEquals(2, tokenRequestBodies.size());
    assertEquals(Arrays.asList(5000L, 5000L, 5000L), ctl.sleeps);
  }

  @Test
  public void cancellationStopsBeforeAnyPoll() throws Exception {
    tokenScript.add(new String[] {"200", "{\"access_token\":\"sk_never\"}"});
    FakeControl ctl = new FakeControl(1000000L);
    ctl.cancelAfterSleeps = 1;
    try {
      DeviceFlow.awaitKey(base, DeviceFlow.requestCode(base), ctl);
      fail("expected ApiError");
    } catch (ApiError e) {
      assertTrue(e.getMessage(), e.getMessage().contains("cancelled"));
    }
    assertEquals(0, tokenRequestBodies.size());
  }

  @Test
  public void serverErrorDuringCodeRequestIsMappedToStatus() throws Exception {
    codeAnswer = new String[] {"500", "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}}"};
    try {
      DeviceFlow.requestCode(base);
      fail("expected ApiError");
    } catch (ApiError e) {
      assertEquals(500, e.status);
      assertEquals("boom", e.getMessage());
    }
  }

  @Test
  public void networkFailureIsStatusZero() throws Exception {
    server.close();
    try {
      DeviceFlow.requestCode(base);
      fail("expected ApiError");
    } catch (ApiError e) {
      assertEquals(0, e.status);
    }
  }

  @Test
  public void pollOnceTreatsNonJsonAsFailedNotCrash() {
    tokenScript.add(new String[] {"500", "<html>oops</html>"});
    DeviceFlow.Poll p = DeviceFlow.pollOnce(base, "dev-123");
    assertEquals(DeviceFlow.FAILED, p.state);
  }

  /** Fake clock: sleep advances time, nothing actually waits. */
  static final class FakeControl implements DeviceFlow.Control {
    long clock;
    final List<Long> sleeps = new ArrayList<Long>();
    int cancelAfterSleeps = -1;

    FakeControl(long start) {
      this.clock = start;
    }

    public long now() {
      return clock;
    }

    public void sleep(long ms) {
      sleeps.add(ms);
      clock += ms;
    }

    public boolean cancelled() {
      return cancelAfterSleeps >= 0 && sleeps.size() >= cancelAfterSleeps;
    }
  }

  /** Minimal HTTP/1.1 server: one request per connection, Connection: close. */
  static final class FakeServer implements Runnable {
    private final ServerSocket socket;
    private final Handler handler;
    private volatile boolean running = true;

    FakeServer(Handler handler) throws IOException {
      this.handler = handler;
      this.socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
      Thread t = new Thread(this, "fake-pollinations");
      t.setDaemon(true);
      t.start();
    }

    int port() {
      return socket.getLocalPort();
    }

    void close() {
      running = false;
      try {
        socket.close();
      } catch (IOException ignored) {
        // Already closed.
      }
    }

    public void run() {
      while (running) {
        try {
          serve(socket.accept());
        } catch (IOException closed) {
          return;
        }
      }
    }

    private void serve(Socket s) throws IOException {
      try {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
        String requestLine = in.readLine();
        if (requestLine == null) {
          return;
        }
        String path = requestLine.split(" ")[1];
        int length = 0;
        String line;
        while ((line = in.readLine()) != null && line.length() > 0) {
          if (line.toLowerCase().startsWith("content-length:")) {
            length = Integer.parseInt(line.substring(15).trim());
          }
        }
        char[] buf = new char[length];
        int read = 0;
        while (read < length) {
          int n = in.read(buf, read, length - read);
          if (n < 0) {
            break;
          }
          read += n;
        }
        String[] answer = handler.respond(path, new String(buf, 0, read));
        byte[] body = answer[1].getBytes(PollinationsApi.UTF8);
        OutputStream out = s.getOutputStream();
        String head =
            "HTTP/1.1 " + answer[0] + " " + reason(Integer.parseInt(answer[0])) + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
      } finally {
        s.close();
      }
    }

    private static String reason(int status) {
      if (status == 200) {
        return "OK";
      }
      if (status == 400) {
        return "Bad Request";
      }
      if (status == 500) {
        return "Internal Server Error";
      }
      return "Status";
    }
  }
}
