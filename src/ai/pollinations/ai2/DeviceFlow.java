package ai.pollinations.ai2;

import ai.pollinations.ai2.PollinationsApi.ApiError;
import ai.pollinations.ai2.PollinationsApi.Response;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Device sign-in against enter.pollinations.ai (see BRING_YOUR_OWN_POLLEN.md, device flow).
 * The state machine is pure: time and cancellation come in through Control, so tests can
 * run it without waiting.
 */
final class DeviceFlow {
  static final long DEFAULT_INTERVAL_MS = 5000L;
  static final long SLOW_DOWN_STEP_MS = 5000L;

  private DeviceFlow() {}

  /** Clock, sleep and cancellation hooks supplied by the caller. */
  interface Control {
    long now();

    void sleep(long ms) throws InterruptedException;

    boolean cancelled();
  }

  /** Result of POST /api/device/code. */
  static final class Code {
    final String deviceCode;
    final String userCode;
    final String verificationUrl;
    final String verificationUrlComplete;
    final long intervalMs;
    final long expiresMs;

    Code(
        String deviceCode,
        String userCode,
        String verificationUrl,
        String verificationUrlComplete,
        long intervalMs,
        long expiresMs) {
      this.deviceCode = deviceCode;
      this.userCode = userCode;
      this.verificationUrl = verificationUrl;
      this.verificationUrlComplete = verificationUrlComplete;
      this.intervalMs = intervalMs;
      this.expiresMs = expiresMs;
    }
  }

  static final int PENDING = 0;
  static final int SLOW_DOWN = 1;
  static final int RETRY = 2;
  static final int DONE = 3;
  static final int FAILED = 4;

  /** Outcome of one POST /api/device/token. */
  static final class Poll {
    final int state;
    final String key;
    final String error;

    Poll(int state, String key, String error) {
      this.state = state;
      this.key = key;
      this.error = error;
    }
  }

  static Code requestCode(String enterBase) throws ApiError {
    Response r = post(enterBase + "/api/device/code", "{}");
    if (r.status != 200) {
      throw PollinationsApi.errorFrom(r);
    }
    try {
      JSONObject o = new JSONObject(r.text());
      long interval = o.optLong("interval", DEFAULT_INTERVAL_MS / 1000L) * 1000L;
      return new Code(
          o.getString("device_code"),
          o.getString("user_code"),
          o.optString("verification_uri", ""),
          o.optString("verification_uri_complete", o.optString("verification_uri", "")),
          interval > 0 ? interval : DEFAULT_INTERVAL_MS,
          o.optLong("expires_in", 1800L) * 1000L);
    } catch (JSONException e) {
      throw new ApiError(-1, "Could not read the sign-in code from Pollinations.");
    }
  }

  /**
   * Polls once. A 4xx answer with an RFC 8628 error code is a normal state, not a failure,
   * so the body is read whatever the status.
   */
  static Poll pollOnce(String enterBase, String deviceCode) {
    Response r;
    try {
      String body = new JSONObject().put("device_code", deviceCode).toString();
      r = post(enterBase + "/api/device/token", body);
    } catch (ApiError e) {
      return new Poll(RETRY, null, e.getMessage());
    } catch (JSONException e) {
      return new Poll(FAILED, null, "Could not build the sign-in request.");
    }
    try {
      JSONObject o = new JSONObject(r.text());
      if (r.status == 200 && o.has("access_token")) {
        return new Poll(DONE, o.getString("access_token"), null);
      }
      String error = o.optString("error", "");
      if ("authorization_pending".equals(error)) {
        return new Poll(PENDING, null, null);
      }
      if ("slow_down".equals(error)) {
        return new Poll(SLOW_DOWN, null, null);
      }
      return new Poll(FAILED, null, describe(error, o.optString("error_description", "")));
    } catch (JSONException e) {
      return new Poll(FAILED, null, "Unexpected answer from Pollinations sign-in (HTTP " + r.status + ").");
    }
  }

  /**
   * Polls until the user approves, the code expires, sign-in is cancelled or the server
   * refuses. The first poll waits one interval, as RFC 8628 asks.
   */
  static String awaitKey(String enterBase, Code code, Control ctl)
      throws ApiError, InterruptedException {
    long deadline = ctl.now() + code.expiresMs;
    long interval = code.intervalMs;
    while (true) {
      ctl.sleep(interval);
      if (ctl.cancelled()) {
        throw new ApiError(-1, "Sign-in was cancelled.");
      }
      if (ctl.now() >= deadline) {
        throw new ApiError(-1, "The sign-in code expired. Start sign-in again.");
      }
      Poll p = pollOnce(enterBase, code.deviceCode);
      if (p.state == DONE) {
        return p.key;
      }
      if (p.state == SLOW_DOWN) {
        interval += SLOW_DOWN_STEP_MS;
      } else if (p.state == FAILED) {
        throw new ApiError(-1, p.error);
      }
    }
  }

  static String describe(String error, String description) {
    if ("expired_token".equals(error)) {
      return "The sign-in code expired. Start sign-in again.";
    }
    if ("access_denied".equals(error)) {
      return "Sign-in was denied in the browser.";
    }
    if ("invalid_grant".equals(error)) {
      return "Sign-in code not recognised. Start sign-in again.";
    }
    if (description.length() > 0) {
      return description;
    }
    return "Sign-in failed" + (error.length() > 0 ? " (" + error + ")" : "") + ".";
  }

  private static Response post(String url, String json) throws ApiError {
    try {
      return PollinationsApi.send(
          "POST", url, null, PollinationsApi.JSON_TYPE, json.getBytes(PollinationsApi.UTF8));
    } catch (IOException e) {
      throw new ApiError(0, "Network error while signing in: " + e.getMessage());
    }
  }

}
