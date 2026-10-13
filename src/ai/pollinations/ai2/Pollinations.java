package ai.pollinations.ai2;

import ai.pollinations.ai2.PollinationsApi.ApiError;
import ai.pollinations.ai2.PollinationsApi.Response;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.Component;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.util.YailList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pollinations AI for App Inventor. Every call runs on a background thread and reports
 * through an event on the UI thread. The API key is never logged.
 */
@DesignerComponent(
    version = 1,
    description =
        "Pollinations AI: text, chat, vision, images, speech and transcription. "
            + "Set ApiKey in the designer or sign in with StartSignIn. Results arrive as events.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public class Pollinations extends AndroidNonvisibleComponent implements Component {

  private static final String GEN = PollinationsApi.GEN_BASE;
  private static final String ENTER = PollinationsApi.ENTER_BASE;
  private static final String PREFS = "pollinations_ai";
  private static final String PREF_KEY = "signed_in_key";
  private static final String TRANSCRIBE_MODEL = "openai/whisper-large-v3";
  private static final String SPEECH_VOICE = "alloy";
  private static final String BOUNDARY = "PollinationsFormBoundary7f3c9a1e";
  private static final int MAX_HISTORY = 20;

  private String apiKey = "";
  private volatile String signedInKey = "";
  private String textModel = "openai/gpt-5-nano";
  private String imageModel = "tongyi-mai/z-image-turbo";
  private String voiceModel = "openai/tts-1";

  private final List<JSONObject> history = new ArrayList<JSONObject>();
  private final ExecutorService chatWorker = Executors.newSingleThreadExecutor();
  private volatile int signInGeneration = 0;

  /** Work that may fail; failures are reported through ErrorOccurred. */
  private interface Work {
    void run() throws Exception;
  }

  public Pollinations(ComponentContainer container) {
    super(container.$form());
    signedInKey = prefs().getString(PREF_KEY, "");
  }

  // --- designer properties --------------------------------------------------

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
  @SimpleProperty(category = PropertyCategory.BEHAVIOR, 
      description =
          "Your Pollinations API key (sk_ or pk_) from https://enter.pollinations.ai/keys. "
              + "Leave empty to use the key from StartSignIn. A key set here is stored in the app.")
  public void ApiKey(String key) {
    apiKey = key == null ? "" : key.trim();
  }

  @DesignerProperty(
      editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "openai/gpt-5-nano")
  @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "Text model for AskText, Chat and AskAboutImage. Must accept images for AskAboutImage.")
  public void TextModel(String model) {
    textModel = model;
  }

  @DesignerProperty(
      editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "tongyi-mai/z-image-turbo")
  @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "Image model for GenerateImage.")
  public void ImageModel(String model) {
    imageModel = model;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "openai/tts-1")
  @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "Speech model for Speak.")
  public void VoiceModel(String model) {
    voiceModel = model;
  }

  // --- text ----------------------------------------------------------------

  @SimpleFunction(description = "Asks the text model one question. The answer arrives in GotText.")
  public void AskText(final String prompt) {
    async(
        new Work() {
          public void run() throws Exception {
            JSONArray messages = new JSONArray().put(PollinationsApi.message("user", prompt));
            gotText(complete(textModel, messages));
          }
        });
  }

  @SimpleFunction(description = "Asks the text model with a system instruction. The answer arrives in GotText.")
  public void AskTextWithSystem(final String system, final String prompt) {
    async(
        new Work() {
          public void run() throws Exception {
            JSONArray messages =
                new JSONArray()
                    .put(PollinationsApi.message("system", system))
                    .put(PollinationsApi.message("user", prompt));
            gotText(complete(textModel, messages));
          }
        });
  }

  @SimpleFunction(
      description =
          "Sends a message in a running chat. The last 20 turns are kept. The answer arrives in GotText.")
  public void Chat(final String message) {
    chatWorker.execute(
        guarded(
            new Work() {
              public void run() throws Exception {
                JSONArray messages = historySnapshot();
                messages.put(PollinationsApi.message("user", message));
                String reply = complete(textModel, messages);
                remember(message, reply);
                gotText(reply);
              }
            }));
  }

  @SimpleFunction(description = "Clears the chat history used by Chat.")
  public synchronized void ClearChat() {
    history.clear();
  }

  @SimpleFunction(
      description =
          "Asks the vision-capable text model about a picture. imagePath can be a file path, "
              + "file:// or content:// URI. The answer arrives in GotText.")
  public void AskAboutImage(final String prompt, final String imagePath) {
    async(
        new Work() {
          public void run() throws Exception {
            String mime = mimeFor(imagePath);
            if (!mime.startsWith("image/")) {
              mime = "image/jpeg";
            }
            String dataUrl =
                "data:" + mime + ";base64," + Base64.encodeToString(readFile(imagePath), Base64.NO_WRAP);
            JSONArray messages = new JSONArray().put(PollinationsApi.imageMessage(prompt, dataUrl));
            gotText(complete(textModel, messages));
          }
        });
  }

  // --- images, speech, transcription --------------------------------------

  @SimpleFunction(
      description =
          "Generates an image and saves it in the app cache. The file:// URI arrives in GotImage; "
              + "set an Image component's Picture to it.")
  public void GenerateImage(final String prompt) {
    async(
        new Work() {
          public void run() throws Exception {
            Response r = call("GET", PollinationsApi.imageUrl(GEN, prompt, imageModel), null, null);
            File file = newCacheFile("image", extensionFor(r.contentType));
            writeFile(file, r.body);
            gotImage(Uri.fromFile(file).toString());
          }
        });
  }

  @SimpleFunction(description = "Reads text aloud as an MP3 file. The file:// URI arrives in GotSpeech.")
  public void Speak(final String text) {
    async(
        new Work() {
          public void run() throws Exception {
            byte[] body = PollinationsApi.speechBody(voiceModel, text, SPEECH_VOICE).getBytes(PollinationsApi.UTF8);
            Response r = call("POST", GEN + "/v1/audio/speech", PollinationsApi.JSON_TYPE, body);
            File file = newCacheFile("speech", "mp3");
            writeFile(file, r.body);
            gotSpeech(Uri.fromFile(file).toString());
          }
        });
  }

  @SimpleFunction(
      description =
          "Transcribes an audio file (mp3, mp4, m4a, wav or webm). The text arrives in GotTranscript.")
  public void Transcribe(final String audioPath) {
    async(
        new Work() {
          public void run() throws Exception {
            String mime = mimeFor(audioPath);
            if (!mime.startsWith("audio/")) {
              mime = "audio/mp4";
            }
            byte[] body =
                PollinationsApi.transcriptionForm(
                    BOUNDARY, TRANSCRIBE_MODEL, fileName(audioPath), mime, readFile(audioPath));
            Response r =
                call("POST", GEN + "/v1/audio/transcriptions", "multipart/form-data; boundary=" + BOUNDARY, body);
            gotTranscript(PollinationsApi.transcriptText(r.text()));
          }
        });
  }

  // --- model lists ---------------------------------------------------------

  @SimpleFunction(
      description =
          "Loads live model names. kind is text, image, speech or transcribe. The list arrives in GotModels.")
  public void LoadModels(final String kind) {
    final String[] query = PollinationsApi.modelQuery(kind);
    if (query == null) {
      reportError(-1, "Unknown kind \"" + kind + "\". Use text, image, speech or transcribe.");
      return;
    }
    async(
        new Work() {
          public void run() throws Exception {
            Response r = call("GET", GEN + query[0], null, null);
            List<String> names = PollinationsApi.modelNames(r.text(), query[1], query[2]);
            gotModels(YailList.makeList(names));
          }
        });
  }

  // --- sign-in without a shipped key --------------------------------------

  @SimpleFunction(
      description =
          "Starts sign-in with a code. SignInCode gives the code and the URL to open; SignedIn fires "
              + "when the user approves. The key is stored privately on the device.")
  public synchronized void StartSignIn() {
    final int generation = ++signInGeneration;
    new Thread(
            new Runnable() {
              public void run() {
                runSignIn(generation);
              }
            })
        .start();
  }

  @SimpleFunction(description = "Forgets the signed-in key and cancels a sign-in that is waiting.")
  public synchronized void SignOut() {
    signInGeneration++;
    signedInKey = "";
    prefs().edit().remove(PREF_KEY).apply();
  }

  private void runSignIn(final int generation) {
    try {
      final DeviceFlow.Code code = DeviceFlow.requestCode(ENTER);
      onUi(
          new Runnable() {
            public void run() {
              SignInCode(code.userCode, code.verificationUrlComplete);
            }
          });
      String key =
          DeviceFlow.awaitKey(
              ENTER,
              code,
              new DeviceFlow.Control() {
                public long now() {
                  return SystemClock.elapsedRealtime();
                }

                public void sleep(long ms) throws InterruptedException {
                  Thread.sleep(ms);
                }

                public boolean cancelled() {
                  return generation != signInGeneration;
                }
              });
      if (generation != signInGeneration) {
        return;
      }
      signedInKey = key;
      prefs().edit().putString(PREF_KEY, key).apply();
      onUi(
          new Runnable() {
            public void run() {
              SignedIn();
            }
          });
    } catch (ApiError e) {
      if (generation == signInGeneration) {
        reportError(e.status, e.getMessage());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // --- events --------------------------------------------------------------

  @SimpleEvent(description = "A text answer from AskText, AskTextWithSystem, Chat or AskAboutImage.")
  public void GotText(String response) {
    EventDispatcher.dispatchEvent(this, "GotText", response);
  }

  @SimpleEvent(description = "A file:// URI of a generated image. Set an Image component's Picture to it.")
  public void GotImage(String path) {
    EventDispatcher.dispatchEvent(this, "GotImage", path);
  }

  @SimpleEvent(description = "A file:// URI of a spoken MP3. Play it with a Player or Sound component.")
  public void GotSpeech(String path) {
    EventDispatcher.dispatchEvent(this, "GotSpeech", path);
  }

  @SimpleEvent(description = "The transcript of an audio file.")
  public void GotTranscript(String text) {
    EventDispatcher.dispatchEvent(this, "GotTranscript", text);
  }

  @SimpleEvent(description = "A list of model names from LoadModels.")
  public void GotModels(YailList models) {
    EventDispatcher.dispatchEvent(this, "GotModels", models);
  }

  @SimpleEvent(
      description =
          "Sign-in code to show the user. Open verificationUrl (it already contains the code).")
  public void SignInCode(String userCode, String verificationUrl) {
    EventDispatcher.dispatchEvent(this, "SignInCode", userCode, verificationUrl);
  }

  @SimpleEvent(description = "Sign-in was approved. The key is now used for all calls.")
  public void SignedIn() {
    EventDispatcher.dispatchEvent(this, "SignedIn");
  }

  @SimpleEvent(
      description =
          "A call failed. errorCode is the HTTP status, 0 for a network failure, or -1 for an unusable answer.")
  public void ErrorOccurred(int errorCode, String message) {
    EventDispatcher.dispatchEvent(this, "ErrorOccurred", errorCode, message);
  }

  // --- helpers -------------------------------------------------------------

  private void async(Work work) {
    new Thread(guarded(work)).start();
  }

  private Runnable guarded(final Work work) {
    return new Runnable() {
      public void run() {
        try {
          work.run();
        } catch (ApiError e) {
          reportError(e.status, e.getMessage());
        } catch (Exception e) {
          reportError(-1, "Unexpected error: " + e.getMessage());
        }
      }
    };
  }

  private void onUi(Runnable r) {
    form.runOnUiThread(r);
  }

  private void reportError(final int code, final String message) {
    onUi(
        new Runnable() {
          public void run() {
            ErrorOccurred(code, message);
          }
        });
  }

  private void gotText(final String text) {
    onUi(
        new Runnable() {
          public void run() {
            GotText(text);
          }
        });
  }

  private void gotImage(final String path) {
    onUi(
        new Runnable() {
          public void run() {
            GotImage(path);
          }
        });
  }

  private void gotSpeech(final String path) {
    onUi(
        new Runnable() {
          public void run() {
            GotSpeech(path);
          }
        });
  }

  private void gotTranscript(final String text) {
    onUi(
        new Runnable() {
          public void run() {
            GotTranscript(text);
          }
        });
  }

  private void gotModels(final YailList models) {
    onUi(
        new Runnable() {
          public void run() {
            GotModels(models);
          }
        });
  }

  /** The key set in the designer wins over the key from sign-in. */
  private String activeKey() {
    return apiKey.length() > 0 ? apiKey : signedInKey;
  }

  private Response call(String method, String url, String contentType, byte[] body) throws ApiError {
    Response r;
    try {
      r = PollinationsApi.send(method, url, activeKey(), contentType, body);
    } catch (IOException e) {
      throw new ApiError(0, "Network error: " + e.getMessage());
    }
    if (r.status < 200 || r.status >= 300) {
      throw PollinationsApi.errorFrom(r);
    }
    return r;
  }

  private String complete(String model, JSONArray messages) throws Exception {
    Response r =
        call(
            "POST",
            GEN + "/v1/chat/completions",
            PollinationsApi.JSON_TYPE,
            PollinationsApi.chatBody(model, messages).getBytes(PollinationsApi.UTF8));
    return PollinationsApi.chatText(r.text());
  }

  private synchronized JSONArray historySnapshot() {
    JSONArray snapshot = new JSONArray();
    for (JSONObject turn : history) {
      snapshot.put(turn);
    }
    return snapshot;
  }

  private synchronized void remember(String userText, String reply) throws Exception {
    history.add(PollinationsApi.message("user", userText));
    history.add(PollinationsApi.message("assistant", reply));
    while (history.size() > MAX_HISTORY) {
      history.remove(0);
    }
  }

  private byte[] readFile(String path) throws IOException {
    InputStream in;
    if (path.startsWith("content://")) {
      in = form.getContentResolver().openInputStream(Uri.parse(path));
      if (in == null) {
        throw new IOException("Cannot open " + path);
      }
    } else {
      String local = path.startsWith("file://") ? Uri.parse(path).getPath() : path;
      in = new FileInputStream(local);
    }
    return PollinationsApi.readAll(in);
  }

  private static void writeFile(File file, byte[] data) throws IOException {
    OutputStream out = new FileOutputStream(file);
    try {
      out.write(data);
    } finally {
      out.close();
    }
  }

  private File newCacheFile(String kind, String extension) {
    File dir = new File(form.getCacheDir(), "pollinations");
    dir.mkdirs();
    return new File(dir, kind + "-" + System.currentTimeMillis() + "." + extension);
  }

  private static String extensionFor(String contentType) {
    if (contentType == null) {
      return "jpg";
    }
    if (contentType.startsWith("image/png")) {
      return "png";
    }
    if (contentType.startsWith("image/webp")) {
      return "webp";
    }
    return "jpg";
  }

  private static String mimeFor(String path) {
    String ext = extensionOf(path);
    if ("jpg".equals(ext) || "jpeg".equals(ext)) {
      return "image/jpeg";
    }
    if ("png".equals(ext)) {
      return "image/png";
    }
    if ("webp".equals(ext)) {
      return "image/webp";
    }
    if ("gif".equals(ext)) {
      return "image/gif";
    }
    if ("mp3".equals(ext) || "mpga".equals(ext) || "mpeg".equals(ext)) {
      return "audio/mpeg";
    }
    if ("m4a".equals(ext) || "mp4".equals(ext)) {
      return "audio/mp4";
    }
    if ("wav".equals(ext)) {
      return "audio/wav";
    }
    if ("webm".equals(ext)) {
      return "audio/webm";
    }
    return "application/octet-stream";
  }

  private static String extensionOf(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
  }

  private static String fileName(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    String name = path.substring(slash + 1);
    return name.length() > 0 ? name : "audio";
  }

  private SharedPreferences prefs() {
    return form.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}
