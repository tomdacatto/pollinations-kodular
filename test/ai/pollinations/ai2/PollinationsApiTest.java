package ai.pollinations.ai2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ai.pollinations.ai2.PollinationsApi.ApiError;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PollinationsApiTest {

  @Test
  public void chatBodyCarriesModelAndMessages() throws Exception {
    JSONArray messages =
        new JSONArray()
            .put(PollinationsApi.message("system", "Be brief."))
            .put(PollinationsApi.message("user", "Hi"));
    JSONObject body = new JSONObject(PollinationsApi.chatBody("openai/gpt-5-nano", messages));
    assertEquals("openai/gpt-5-nano", body.getString("model"));
    assertEquals(2, body.getJSONArray("messages").length());
    assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"));
    assertEquals("Hi", body.getJSONArray("messages").getJSONObject(1).getString("content"));
  }

  @Test
  public void imageMessageUsesOpenAiVisionShape() throws Exception {
    String dataUrl = "data:image/png;base64,AAAA";
    JSONObject msg = PollinationsApi.imageMessage("What is this?", dataUrl);
    assertEquals("user", msg.getString("role"));
    JSONArray parts = msg.getJSONArray("content");
    assertEquals("text", parts.getJSONObject(0).getString("type"));
    assertEquals("What is this?", parts.getJSONObject(0).getString("text"));
    assertEquals("image_url", parts.getJSONObject(1).getString("type"));
    assertEquals(dataUrl, parts.getJSONObject(1).getJSONObject("image_url").getString("url"));
  }

  @Test
  public void imageUrlEncodesPromptWithPercent20AndModelSlash() {
    String url = PollinationsApi.imageUrl(PollinationsApi.GEN_BASE, "a red fox & moon", "tongyi-mai/z-image-turbo");
    assertEquals(
        "https://gen.pollinations.ai/image/a%20red%20fox%20%26%20moon?model=tongyi-mai%2Fz-image-turbo",
        url);
  }

  @Test
  public void imageUrlOmitsModelWhenBlank() {
    assertEquals("http://x/image/cat", PollinationsApi.imageUrl("http://x", "cat", ""));
  }

  @Test
  public void chatTextReturnsContent() throws Exception {
    String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello there\"}}]}";
    assertEquals("Hello there", PollinationsApi.chatText(body));
  }

  @Test
  public void chatTextRejectsEmptyContentInsteadOfReturningBlank() {
    expectError(
        () -> PollinationsApi.chatText("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"), -1);
    expectError(() -> PollinationsApi.chatText("{\"choices\":[]}"), -1);
    expectError(() -> PollinationsApi.chatText("not json"), -1);
  }

  @Test
  public void mapsPaymentErrorCodesToFriendlyMessages() {
    assertContains(
        PollinationsApi.friendlyMessage(
            402, "{\"error\":{\"code\":\"INSUFFICIENT_BALANCE\",\"message\":\"x\"}}"),
        "Not enough Pollen");
    assertContains(
        PollinationsApi.friendlyMessage(402, "{\"error\":{\"code\":\"KEY_BUDGET_EXHAUSTED\"}}"),
        "budget");
    assertContains(
        PollinationsApi.friendlyMessage(402, "{\"error\":{\"code\":\"QUEST_POLLEN_ONLY\"}}"),
        "Quest Pollen");
    assertContains(PollinationsApi.friendlyMessage(402, "{}"), "Add Pollen");
  }

  @Test
  public void mapsAuthRateAndUnknownErrors() {
    assertContains(PollinationsApi.friendlyMessage(401, ""), "API key");
    assertContains(PollinationsApi.friendlyMessage(429, ""), "Too many requests");
    assertEquals(
        "bad model",
        PollinationsApi.friendlyMessage(400, "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"bad model\"}}"));
    assertEquals(
        "Pollinations request failed with HTTP 500.", PollinationsApi.friendlyMessage(500, "<html>"));
  }

  @Test
  public void modelNamesFilterByCategoryAndEndpoint() throws Exception {
    String body =
        "["
            + "{\"name\":\"a/text-one\",\"category\":\"text\",\"supported_endpoints\":[\"/v1/chat/completions\"]},"
            + "{\"name\":\"b/image-one\",\"category\":\"image\",\"supported_endpoints\":[\"/image/{prompt}\"]},"
            + "{\"name\":\"c/video-one\",\"category\":\"video\",\"supported_endpoints\":[\"/video/{prompt}\"]},"
            + "{\"name\":\"d/tts\",\"category\":\"audio\",\"supported_endpoints\":[\"/v1/audio/speech\"]},"
            + "{\"name\":\"e/stt\",\"category\":\"audio\",\"supported_endpoints\":[\"/v1/audio/transcriptions\"]}"
            + "]";
    assertEquals(Arrays.asList("b/image-one"), PollinationsApi.modelNames(body, "image", null));
    assertEquals(Arrays.asList("d/tts"), PollinationsApi.modelNames(body, "audio", "/v1/audio/speech"));
    assertEquals(
        Arrays.asList("e/stt"), PollinationsApi.modelNames(body, "audio", "/v1/audio/transcriptions"));
    assertEquals(
        Arrays.asList("a/text-one"), PollinationsApi.modelNames(body, "text", "/v1/chat/completions"));
  }

  @Test
  public void modelQueryKnowsFourKindsAndRejectsOthers() {
    assertEquals("/text/models", PollinationsApi.modelQuery("text")[0]);
    assertEquals("/image/models", PollinationsApi.modelQuery("image")[0]);
    assertEquals("/v1/audio/speech", PollinationsApi.modelQuery("speech")[2]);
    assertEquals("/v1/audio/transcriptions", PollinationsApi.modelQuery("transcribe")[2]);
    assertTrue(PollinationsApi.modelQuery("video") == null);
  }

  @Test
  public void speechBodyAsksForMp3() throws Exception {
    JSONObject body = new JSONObject(PollinationsApi.speechBody("openai/tts-1", "Hi", "alloy"));
    assertEquals("openai/tts-1", body.getString("model"));
    assertEquals("Hi", body.getString("input"));
    assertEquals("alloy", body.getString("voice"));
    assertEquals("mp3", body.getString("response_format"));
  }

  @Test
  public void transcriptionFormHasModelFieldAndExactFileBytes() throws Exception {
    byte[] audio = {0x01, 0x02, (byte) 0xff, 0x00};
    byte[] form =
        PollinationsApi.transcriptionForm("BOUNDARY", "openai/whisper-large-v3", "say \"hi\".m4a", "audio/mp4", audio);
    String asText = new String(form, Charset.forName("ISO-8859-1"));
    assertTrue(asText.startsWith("--BOUNDARY\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nopenai/whisper-large-v3\r\n"));
    assertTrue(asText.contains("name=\"file\"; filename=\"say _hi_.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"));
    assertTrue(asText.endsWith("\r\n--BOUNDARY--\r\n"));
    // The raw audio bytes sit between the file header and the closing boundary.
    int start = asText.indexOf("\r\n\r\n", asText.indexOf("filename=")) + 4;
    int end = asText.lastIndexOf("\r\n--BOUNDARY--");
    assertEquals(
        Arrays.toString(audio),
        Arrays.toString(Arrays.copyOfRange(form, start, end)));
  }

  @Test
  public void transcriptTextReadsTextField() throws Exception {
    assertEquals("hello world", PollinationsApi.transcriptText("{\"text\":\"hello world\"}"));
    expectError(() -> PollinationsApi.transcriptText("<xml/>"), -1);
  }

  private static void assertContains(String actual, String expected) {
    assertTrue("expected '" + actual + "' to contain '" + expected + "'", actual.contains(expected));
  }

  interface Call {
    void run() throws Exception;
  }

  private static void expectError(Call call, int expectedStatus) {
    try {
      call.run();
      fail("expected ApiError");
    } catch (ApiError e) {
      assertEquals(expectedStatus, e.status);
    } catch (Exception e) {
      fail("unexpected " + e);
    }
  }
}
