package ai.pollinations.ai2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * HTTP and JSON core for the Pollinations extension.
 *
 * <p>This class must not import android.* so the plain JVM unit tests can run it.
 * Status 0 in an ApiError means no HTTP answer (network failure); -1 means the
 * local side could not use the answer.
 */
final class PollinationsApi {
  static final String GEN_BASE = "https://gen.pollinations.ai";
  static final String ENTER_BASE = "https://enter.pollinations.ai";
  static final String UTF8 = "UTF-8";
  static final String JSON_TYPE = "application/json";

  private PollinationsApi() {}

  /** A failed call: HTTP status (0 = network, -1 = unusable answer) plus a user-facing message. */
  static final class ApiError extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;

    ApiError(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  /** A completed HTTP exchange. */
  static final class Response {
    final int status;
    final byte[] body;
    final String contentType;

    Response(int status, byte[] body, String contentType) {
      this.status = status;
      this.body = body;
      this.contentType = contentType;
    }

    String text() {
      try {
        return new String(body, UTF8);
      } catch (UnsupportedEncodingException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * Sends one request. The key, when present, goes only in the Authorization header.
   * Never log the arguments of this method.
   */
  static Response send(String method, String url, String key, String contentType, byte[] body)
      throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    try {
      conn.setRequestMethod(method);
      conn.setConnectTimeout(20000);
      conn.setReadTimeout(180000);
      if (key != null && key.length() > 0) {
        conn.setRequestProperty("Authorization", "Bearer " + key);
      }
      if (contentType != null) {
        conn.setRequestProperty("Content-Type", contentType);
      }
      if (body != null) {
        conn.setDoOutput(true);
        OutputStream out = conn.getOutputStream();
        try {
          out.write(body);
        } finally {
          out.close();
        }
      }
      int status = conn.getResponseCode();
      InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
      byte[] data = in == null ? new byte[0] : readAll(in);
      return new Response(status, data, conn.getContentType());
    } finally {
      conn.disconnect();
    }
  }

  static byte[] readAll(InputStream in) throws IOException {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    } finally {
      in.close();
    }
  }

  /** Maps a non-2xx gen.pollinations.ai answer to a friendly error. */
  static ApiError errorFrom(Response r) {
    return new ApiError(r.status, friendlyMessage(r.status, r.text()));
  }

  static String friendlyMessage(int status, String body) {
    String code = "";
    String serverMessage = "";
    try {
      JSONObject err = new JSONObject(body).optJSONObject("error");
      if (err != null) {
        code = err.optString("code", "");
        serverMessage = err.optString("message", "");
      }
    } catch (JSONException e) {
      // Not a JSON error body; fall through to the status-only message.
    }
    if (status == 402) {
      if ("INSUFFICIENT_BALANCE".equals(code)) {
        return "Not enough Pollen. Add Pollen at https://enter.pollinations.ai to continue.";
      }
      if ("QUEST_POLLEN_ONLY".equals(code)) {
        return "Quest Pollen cannot pay for this model. Add Pollen at https://enter.pollinations.ai.";
      }
      if ("KEY_BUDGET_EXHAUSTED".equals(code)) {
        return "This API key has used its budget. Raise it at https://enter.pollinations.ai/keys.";
      }
      return "This needs Pollen. Add Pollen at https://enter.pollinations.ai.";
    }
    if (status == 401) {
      return "The API key is missing or invalid. Set ApiKey or sign in again.";
    }
    if (status == 429) {
      return "Too many requests. Wait a moment and try again.";
    }
    if (serverMessage.length() > 0) {
      return serverMessage;
    }
    return "Pollinations request failed with HTTP " + status + ".";
  }

  static JSONObject message(String role, String content) throws JSONException {
    return new JSONObject().put("role", role).put("content", content);
  }

  /** A user message with text and one image as a data URL (OpenAI vision format). */
  static JSONObject imageMessage(String prompt, String dataUrl) throws JSONException {
    JSONArray parts =
        new JSONArray()
            .put(new JSONObject().put("type", "text").put("text", prompt))
            .put(
                new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", dataUrl)));
    return new JSONObject().put("role", "user").put("content", parts);
  }

  static String chatBody(String model, JSONArray messages) throws JSONException {
    return new JSONObject().put("model", model).put("messages", messages).toString();
  }

  /** Extracts choices[0].message.content. Empty content is an error, not a silent blank. */
  static String chatText(String body) throws ApiError {
    try {
      JSONArray choices = new JSONObject(body).optJSONArray("choices");
      if (choices != null && choices.length() > 0) {
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg != null) {
          String content = msg.optString("content", "");
          if (content.length() > 0) {
            return content;
          }
        }
      }
    } catch (JSONException e) {
      throw new ApiError(-1, "Could not read the reply from Pollinations.");
    }
    throw new ApiError(-1, "The model returned no text. Try again or choose another model.");
  }

  /** GET URL for /image/{prompt}. The prompt is percent-encoded with %20 for spaces. */
  static String imageUrl(String base, String prompt, String model) {
    String url = base + "/image/" + encode(prompt).replace("+", "%20");
    if (model != null && model.length() > 0) {
      url += "?model=" + encode(model);
    }
    return url;
  }

  static String speechBody(String model, String input, String voice) throws JSONException {
    return new JSONObject()
        .put("model", model)
        .put("input", input)
        .put("voice", voice)
        .put("response_format", "mp3")
        .toString();
  }

  /** multipart/form-data body with a model field and one audio file part. */
  static byte[] transcriptionForm(
      String boundary, String model, String fileName, String fileType, byte[] audio)
      throws IOException {
    String safeName = fileName.replace("\"", "_");
    String head =
        "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
            + model + "\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
            + "Content-Type: " + fileType + "\r\n\r\n";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(head.getBytes(UTF8));
    out.write(audio);
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(UTF8));
    return out.toByteArray();
  }

  static String transcriptText(String body) throws ApiError {
    try {
      return new JSONObject(body).optString("text", "");
    } catch (JSONException e) {
      throw new ApiError(-1, "Could not read the transcript from Pollinations.");
    }
  }

  /** Query for a live model list: path, category filter and endpoint filter, or null for an unknown kind. */
  static String[] modelQuery(String kind) {
    if ("text".equals(kind)) {
      return new String[] {"/text/models", "text", "/v1/chat/completions"};
    }
    if ("image".equals(kind)) {
      return new String[] {"/image/models", "image", null};
    }
    if ("speech".equals(kind)) {
      return new String[] {"/audio/models", "audio", "/v1/audio/speech"};
    }
    if ("transcribe".equals(kind)) {
      return new String[] {"/audio/models", "audio", "/v1/audio/transcriptions"};
    }
    return null;
  }

  /** Names from a model list, filtered by category and by a supported endpoint. */
  static List<String> modelNames(String body, String category, String endpoint) throws ApiError {
    List<String> names = new ArrayList<String>();
    try {
      JSONArray items = new JSONArray(body);
      for (int i = 0; i < items.length(); i++) {
        JSONObject model = items.getJSONObject(i);
        if (category != null && !category.equals(model.optString("category"))) {
          continue;
        }
        if (endpoint != null && !supports(model, endpoint)) {
          continue;
        }
        names.add(model.getString("name"));
      }
    } catch (JSONException e) {
      throw new ApiError(-1, "Could not read the model list from Pollinations.");
    }
    return names;
  }

  private static boolean supports(JSONObject model, String endpoint) {
    JSONArray endpoints = model.optJSONArray("supported_endpoints");
    if (endpoints == null) {
      return false;
    }
    for (int i = 0; i < endpoints.length(); i++) {
      if (endpoint.equals(endpoints.optString(i))) {
        return true;
      }
    }
    return false;
  }

  static String encode(String s) {
    try {
      return URLEncoder.encode(s, UTF8);
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
