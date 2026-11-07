package app.kolay.app.tools.halal;

import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Open Food Facts product lookup (public, HTTPS). Only the barcode digits leave the device. */
final class Off {
    private Off() {}

    static final class Product {
        final String name, brand, ingredients;
        Product(String name, String brand, String ingredients) { this.name = name; this.brand = brand; this.ingredients = ingredients; }
    }

    /** @return the product, or null when the barcode is unknown. Throws on network trouble. */
    static Product lookup(String code) throws IOException {
        if (!code.matches("\\d{6,14}")) return null;
        String url = "https://world.openfoodfacts.org/api/v2/product/" + code
            + ".json?fields=product_name,brands,ingredients_text,ingredients_text_tr,ingredients_text_de,ingredients_text_en";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Kolay/1.0 (Android; halal ingredient check)");
        int status = c.getResponseCode();
        if (status == 404) return null;
        if (status >= 400) throw new IOException("Ürün veritabanı HTTP " + status);
        String body;
        try (InputStream is = c.getInputStream()) { body = app.kolay.app.tools.Llm.readUtf8(is); }
        try {
            JSONObject root = new JSONObject(body);
            if (root.optInt("status", 0) != 1) return null;
            JSONObject p = root.getJSONObject("product");
            String ing = "";
            for (String k : new String[]{"ingredients_text_tr", "ingredients_text", "ingredients_text_de", "ingredients_text_en"}) {
                String v = p.optString(k, "").trim();
                if (v.length() >= 3) { ing = v; break; }
            }
            return new Product(p.optString("product_name", "").trim(), p.optString("brands", "").trim(), ing);
        } catch (org.json.JSONException e) {
            throw new IOException("Ürün veritabanı yanıtı okunamadı");
        }
    }
}
