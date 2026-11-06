package app.kolay.app.tools;

import app.kolay.app.screens.SettingsScreen;
import app.kolay.shell.Host;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Minimal Gemini calls (text, single prompt or multi-turn chat) using the key from Settings. Nothing is logged or stored. */
public final class Llm {
    private static final String MODEL = "gemini-flash-lite-latest";
    private Llm() {}

    public static String generate(Host host, String prompt) throws Exception {
        return generate(host, prompt, 0.2);
    }

    /** Text in, text out. Low {@code temperature} for tasks that must stay faithful (verdicts, translation). */
    public static String generate(Host host, String prompt, double temperature) throws Exception {
        return generate(host, prompt, temperature, 8192);
    }

    /** As above with a larger output budget (long documents). */
    public static String generate(Host host, String prompt, double temperature, int maxTokens) throws Exception {
        JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
        return call(host, new JSONArray().put(new JSONObject().put("parts", parts)), null, temperature, maxTokens, 120000);
    }

    /**
     * Multi-turn chat. {@code turns} alternate user / model, oldest first, and must end with the user's new message;
     * each entry is {role ("user" or "model"), text}. No system instruction is sent.
     */
    public static String chat(Host host, List<String[]> turns, double temperature) throws Exception {
        JSONArray contents = new JSONArray();
        for (String[] t : turns)
            contents.put(new JSONObject().put("role", t[0]).put("parts", new JSONArray().put(new JSONObject().put("text", t[1]))));
        return call(host, contents, null, temperature, 4096, 90000);
    }

    public static boolean hasKey(Host host) { return !host.secret(SettingsScreen.LLM_SECRET).isEmpty(); }

    private static String call(Host host, JSONArray contents, String system, double temperature, int maxTokens, int readTimeoutMs) throws Exception {
        String key = host.secret(SettingsScreen.LLM_SECRET);
        if (key.isEmpty()) throw new IOException("Anahtar girilmemiş (Ayarlar)");
        JSONObject body = new JSONObject()
            .put("contents", contents)
            .put("generationConfig", new JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens));
        if (system != null && !system.isEmpty())
            body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String resp;
        try (InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);   // error stream is null when the body is empty
        } finally { c.disconnect(); }
        if (code == 429) throw new IOException("Kullanım sınırı doldu; biraz sonra tekrar dene");
        if (code == 401 || code == 403 || (code == 400 && resp.contains("API key")))
            throw new IOException("Anahtar kabul edilmedi (HTTP " + code + "); Ayarlar'dan kontrol et");
        if (code == 400) throw new IOException("İstek reddedildi (HTTP 400)");
        if (code >= 400) throw new IOException("Sunucu hatası (HTTP " + code + ")");
        JSONArray cand = new JSONObject(resp).optJSONArray("candidates");
        if (cand == null || cand.length() == 0) throw new IOException("Yanıt gelmedi (içerik engellenmiş olabilir)");
        JSONObject content = cand.getJSONObject(0).optJSONObject("content");
        if (content == null) throw new IOException("Boş yanıt döndü");
        JSONArray out = content.getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.length(); i++) sb.append(out.getJSONObject(i).optString("text"));
        return sb.toString().trim();
    }
}
