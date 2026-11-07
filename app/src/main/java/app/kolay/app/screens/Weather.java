package app.kolay.app.screens;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 24-hour forecast for the home hero from Open-Meteo (public, HTTPS, no key). Only the saved prayer location, rounded to
 * two decimals (about 1 km), leaves the device. The raw answer is cached in the "weather" preferences for {@link #FRESH_MS};
 * an older copy (up to {@link #KEEP_MS}) is still shown with the past hours dropped. Every failure ends in null.
 */
final class Weather {
    static final long FRESH_MS = 45 * 60_000L, KEEP_MS = 12 * 3600_000L;

    final double temp;          // now
    final int code;             // WMO weather code, now
    final boolean day;
    final String[] hour;        // "14"
    final double[] t, mm;
    final int[] prob, codes;
    final boolean[] days;

    private Weather(double temp, int code, boolean day, String[] hour, double[] t, double[] mm, int[] prob, int[] codes, boolean[] days) {
        this.temp = temp; this.code = code; this.day = day;
        this.hour = hour; this.t = t; this.mm = mm; this.prob = prob; this.codes = codes; this.days = days;
    }

    static String key(double[] loc) { return String.format(Locale.US, "%.2f,%.2f", loc[0], loc[1]); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("weather", Context.MODE_PRIVATE); }

    /** Age of the cached answer for this location in ms, or -1 when there is none. */
    static long age(Context c, String key) {
        SharedPreferences p = prefs(c);
        if (!key.equals(p.getString("key", ""))) return -1;
        long at = p.getLong("at", 0);
        return at <= 0 ? -1 : Math.max(0, System.currentTimeMillis() - at);
    }

    /** The cached forecast (past hours dropped), or null when missing, older than {@link #KEEP_MS} or unreadable. */
    static Weather cached(Context c, String key) {
        long age = age(c, key);
        if (age < 0 || age > KEEP_MS) return null;
        return parse(prefs(c).getString("json", ""), (int) (age / 3600_000L));
    }

    /** Network call (background thread only); stores the answer when it parses. */
    static void fetch(Context c, String key) throws IOException {
        String[] ll = key.split(",");
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + ll[0] + "&longitude=" + ll[1]
            + "&current=temperature_2m,weathercode,is_day"
            + "&hourly=temperature_2m,precipitation_probability,precipitation,weathercode,is_day"
            + "&forecast_hours=24&timezone=auto";
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(8000); h.setReadTimeout(10000);
            h.setRequestProperty("User-Agent", "Kolay/1.0 (Android; weather)");
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            String body;
            try (InputStream is = h.getInputStream()) { body = app.kolay.app.tools.Llm.readUtf8(is); }
            if (parse(body, 0) == null) throw new IOException("bad answer");
            prefs(c).edit().putString("key", key).putLong("at", System.currentTimeMillis()).putString("json", body).apply();
        } finally { h.disconnect(); }
    }

    private static Weather parse(String body, int skip) {
        try {
            JSONObject root = new JSONObject(body), cur = root.getJSONObject("current"), hr = root.getJSONObject("hourly");
            JSONArray time = hr.getJSONArray("time"), te = hr.getJSONArray("temperature_2m"), pr = hr.getJSONArray("precipitation_probability"),
                pm = hr.getJSONArray("precipitation"), wc = hr.getJSONArray("weathercode"), dy = hr.getJSONArray("is_day");
            int n = time.length() - skip;
            if (n <= 0) return null;
            String[] hour = new String[n]; double[] t = new double[n], mm = new double[n]; int[] prob = new int[n], codes = new int[n];
            boolean[] days = new boolean[n];
            for (int i = 0; i < n; i++) {
                int j = i + skip;
                String s = time.getString(j);
                hour[i] = s.length() >= 13 ? s.substring(11, 13) : s;
                t[i] = te.getDouble(j); mm[i] = pm.optDouble(j, 0); prob[i] = pr.optInt(j, 0); codes[i] = wc.getInt(j); days[i] = dy.optInt(j, 1) == 1;
            }
            if (skip == 0) return new Weather(cur.getDouble("temperature_2m"), cur.getInt("weathercode"), cur.optInt("is_day", 1) == 1,
                hour, t, mm, prob, codes, days);
            return new Weather(t[0], codes[0], days[0], hour, t, mm, prob, codes, days);
        } catch (org.json.JSONException | RuntimeException e) { return null; }
    }

    /** Symbol for a WMO weather code. */
    static String symbol(int code, boolean day) {
        if (code <= 1) return day ? (code == 0 ? "☀️" : "🌤️") : "🌙";
        if (code == 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        if (code >= 80 && code <= 82 || code >= 51 && code <= 57) return day ? "🌦️" : "🌧️";
        return "🌧️";
    }

    /** Short Turkish condition for a WMO weather code. */
    static String label(int code) {
        if (code == 0) return "Açık";
        if (code == 1) return "Az bulutlu";
        if (code == 2) return "Parçalı bulutlu";
        if (code == 3) return "Kapalı";
        if (code == 45 || code == 48) return "Sisli";
        if (code >= 51 && code <= 57) return "Çisenti";
        if (code >= 61 && code <= 67) return "Yağmurlu";
        if (code >= 71 && code <= 77) return "Karlı";
        if (code >= 80 && code <= 82) return "Sağanak";
        if (code == 85 || code == 86) return "Kar sağanağı";
        if (code >= 95) return "Gök gürültülü";
        return "";
    }
}
