package ai.pollinations.ai2;

import ai.pollinations.ai2.PollinationsApi.Response;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;

/**
 * Manual live check against gen.pollinations.ai through the same core classes the component uses.
 * Not run by run-tests.sh. The key is read from POLLINATIONS_API_KEY and is never printed.
 *
 * <p>Usage: POLLINATIONS_API_KEY=... java ... ai.pollinations.ai2.LiveCheck OUT_DIR
 *
 * <p>Keyed calls: text, image, vision, speech, transcription (5). The model list is a public GET
 * without a key.
 */
public class LiveCheck {
  static final String TEXT_MODEL = "amazon/nova-micro-v1";
  static final String VISION_MODEL = "openai/gpt-5-nano";
  static final String IMAGE_MODEL = "tongyi-mai/z-image-turbo";
  static final String SPEECH_MODEL = "openai/tts-1";
  static final String STT_MODEL = "openai/whisper-large-v3";

  public static void main(String[] args) throws Exception {
    String key = System.getenv("POLLINATIONS_API_KEY");
    if (key == null || key.length() == 0) {
      System.out.println("FAIL: POLLINATIONS_API_KEY is not set");
      System.exit(2);
    }
    File out = new File(args[0]);
    out.mkdirs();
    int failures = 0;

    // 1. Live model list (public GET, no key).
    try {
      Response r = PollinationsApi.send("GET", PollinationsApi.GEN_BASE + "/text/models", null, null, null);
      if (r.status != 200) {
        throw PollinationsApi.errorFrom(r);
      }
      List<String> names = PollinationsApi.modelNames(r.text(), "text", "/v1/chat/completions");
      System.out.println("MODELS  status=" + r.status + " chatTextModels=" + names.size() + " first=" + names.subList(0, Math.min(3, names.size())));
    } catch (PollinationsApi.ApiError e) {
      failures++;
      System.out.println("MODELS  FAIL status=" + e.status + " " + e.getMessage());
    }

    // 2. Text chat with the cheapest healthy general text model.
    try {
      JSONArray messages = new JSONArray().put(PollinationsApi.message("user", "Reply with exactly the word: pong"));
      Response r = callPath("POST", "/v1/chat/completions", PollinationsApi.JSON_TYPE,
          PollinationsApi.chatBody(TEXT_MODEL, messages).getBytes(PollinationsApi.UTF8), key);
      System.out.println("TEXT    status=" + r.status + " reply=" + PollinationsApi.chatText(r.text()).replace('\n', ' ').trim());
    } catch (PollinationsApi.ApiError e) {
      failures++;
      System.out.println("TEXT    FAIL status=" + e.status + " " + e.getMessage());
    }

    // 3. Image route.
    byte[] image = null;
    try {
      Response r = call("GET", PollinationsApi.imageUrl(PollinationsApi.GEN_BASE, "a small red fox on a green hill", IMAGE_MODEL),
          null, null, key);
      image = r.body;
      String ext = r.contentType != null && r.contentType.startsWith("image/png") ? "png" : "jpg";
      File file = new File(out, "fox." + ext);
      write(file, image);
      System.out.println("IMAGE   status=" + r.status + " type=" + r.contentType + " bytes=" + image.length + " saved=" + file.getName());
      image = r.body;
      imageExt = ext;
    } catch (PollinationsApi.ApiError e) {
      failures++;
      System.out.println("IMAGE   FAIL status=" + e.status + " " + e.getMessage());
    }

    // 4. Vision: the picture from step 3 goes to a vision-capable text model as a data URL.
    if (image != null) {
      try {
        String mime = "png".equals(imageExt) ? "image/png" : "image/jpeg";
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);
        JSONArray messages = new JSONArray().put(PollinationsApi.imageMessage("What animal is in this picture? Answer in one word.", dataUrl));
        Response r = callPath("POST", "/v1/chat/completions", PollinationsApi.JSON_TYPE,
            PollinationsApi.chatBody(VISION_MODEL, messages).getBytes(PollinationsApi.UTF8), key);
        System.out.println("VISION  status=" + r.status + " reply=" + PollinationsApi.chatText(r.text()).replace('\n', ' ').trim());
      } catch (PollinationsApi.ApiError e) {
        failures++;
        System.out.println("VISION  FAIL status=" + e.status + " " + e.getMessage());
      }
    } else {
      System.out.println("VISION  SKIPPED (no image from step 3)");
    }

    // 5. Speech.
    byte[] speech = null;
    try {
      Response r = callPath("POST", "/v1/audio/speech", PollinationsApi.JSON_TYPE,
          PollinationsApi.speechBody(SPEECH_MODEL, "Hello from Pollinations.", "alloy").getBytes(PollinationsApi.UTF8), key);
      speech = r.body;
      write(new File(out, "speech.mp3"), speech);
      System.out.println("SPEECH  status=" + r.status + " type=" + r.contentType + " bytes=" + speech.length);
    } catch (PollinationsApi.ApiError e) {
      failures++;
      System.out.println("SPEECH  FAIL status=" + e.status + " " + e.getMessage());
    }

    // 6. Transcription of the speech from step 5 (multipart upload).
    if (speech != null) {
      try {
        String boundary = "PollinationsLiveBoundary" + System.nanoTime();
        byte[] body = PollinationsApi.transcriptionForm(boundary, STT_MODEL, "speech.mp3", "audio/mpeg", speech);
        Response r = callPath("POST", "/v1/audio/transcriptions", "multipart/form-data; boundary=" + boundary, body, key);
        System.out.println("STT     status=" + r.status + " transcript=" + PollinationsApi.transcriptText(r.text()).trim());
      } catch (PollinationsApi.ApiError e) {
        failures++;
        System.out.println("STT     FAIL status=" + e.status + " " + e.getMessage());
      }
    } else {
      System.out.println("STT     SKIPPED (no audio from step 5)");
    }

    System.out.println(failures == 0 ? "LIVE RESULT: all steps OK" : "LIVE RESULT: " + failures + " step(s) failed");
    System.exit(failures == 0 ? 0 : 1);
  }

  private static String imageExt = "jpg";

  private static Response callPath(String method, String path, String contentType, byte[] body, String key)
      throws PollinationsApi.ApiError {
    return call(method, PollinationsApi.GEN_BASE + path, contentType, body, key);
  }

  private static Response call(String method, String url, String contentType, byte[] body, String key)
      throws PollinationsApi.ApiError {
    try {
      Response r = PollinationsApi.send(method, url, key, contentType, body);
      if (r.status < 200 || r.status >= 300) {
        throw PollinationsApi.errorFrom(r);
      }
      return r;
    } catch (java.io.IOException e) {
      throw new PollinationsApi.ApiError(0, "Network error: " + e.getMessage());
    }
  }

  private static void write(File file, byte[] data) throws java.io.IOException {
    OutputStream out = new FileOutputStream(file);
    try {
      out.write(data);
    } finally {
      out.close();
    }
  }
}
