package app.kolay.app.tools.youtube;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches a video's caption track in its original (spoken) language via YouTube's player endpoint. Pure Java, nothing stored. */
final class Transcript {
    private static final String UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";
    private static final Pattern ID = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})");
    private static final Pattern BARE = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern CUE = Pattern.compile("<(?:text|p)\\b[^>]*>(.*?)</(?:text|p)>", Pattern.DOTALL);

    private Transcript() {}

    /** @return the 11-char video id from a pasted link (or a bare id), or null. */
    static String videoId(String in) {
        if (in == null) return null;
        String s = in.trim();
        Matcher m = ID.matcher(s);
        if (m.find()) return m.group(1);
        return BARE.matcher(s).matches() ? s : null;
    }

    static final class Result {
        final String text, lang;
        Result(String text, String lang) { this.text = text; this.lang = lang; }
    }

    static Result fetch(String id) throws Exception {
        JSONObject body = new JSONObject()
            .put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "ANDROID").put("clientVersion", "20.10.38").put("androidSdkVersion", 34).put("hl", "en")))
            .put("videoId", id);
        JSONObject player = new JSONObject(post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString()));
        JSONObject pr = player.optJSONObject("captions") == null ? null : player.getJSONObject("captions").optJSONObject("playerCaptionsTracklistRenderer");
        JSONArray tracks = pr == null ? null : pr.optJSONArray("captionTracks");
        if (tracks == null || tracks.length() == 0) {
            String why = player.optJSONObject("playabilityStatus") == null ? "" : player.getJSONObject("playabilityStatus").optString("reason");
            throw new IOException(why.isEmpty() ? "Bu videoda altyazı/transkript yok" : why);
        }
        JSONObject t = original(pr, tracks);
        String text = parse(get(t.getString("baseUrl")));
        if (text.isEmpty()) throw new IOException("Transkript boş döndü");
        return new Result(text, t.optString("languageCode"));
    }

    /** The spoken-language track: the auto-generated (asr) one is by definition the original; else the audio default; else the first. */
    private static JSONObject original(JSONObject pr, JSONArray tracks) throws Exception {
        for (int i = 0; i < tracks.length(); i++) if ("asr".equals(tracks.getJSONObject(i).optString("kind"))) return tracks.getJSONObject(i);
        JSONArray audio = pr.optJSONArray("audioTracks");
        if (audio != null && audio.length() > 0) {
            int idx = audio.getJSONObject(0).optInt("defaultCaptionTrackIndex", 0);
            if (idx >= 0 && idx < tracks.length()) return tracks.getJSONObject(idx);
        }
        return tracks.getJSONObject(0);
    }

    static String parse(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = CUE.matcher(xml);
        while (m.find()) {
            String line = unescape(unescape(m.group(1).replaceAll("<[^>]+>", ""))).replace('\n', ' ').trim();
            if (!line.isEmpty()) sb.append(line).append(' ');
        }
        return sb.toString().trim();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String get(String url) throws Exception { return send(url, null); }
    private static String post(String url, String json) throws Exception { return send(url, json); }

    private static String send(String url, String json) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return sendOnce(url, json); }
            catch (IOException e) {   // 5xx from YouTube is usually momentary: retry twice
                if (attempt >= 2 || !e.getMessage().matches("YouTube HTTP 5\\d\\d")) throw e;
                Thread.sleep(1000L * (attempt + 1));
            }
        }
    }

    private static String sendOnce(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        if (json != null) {
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        String resp;
        try (InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            resp = is == null ? "" : app.kolay.app.tools.Llm.readUtf8(is);   // error stream is null when the body is empty
        } finally { c.disconnect(); }
        if (code >= 400) throw new IOException("YouTube HTTP " + code);
        return resp;
    }
}
