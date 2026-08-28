/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.nativeFeatures.translator.providers;

import static app.morphe.extension.shared.StringRef.str;

import android.os.AsyncTask;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import app.morphe.extension.twitter.Pref;

/*
* DeepL API v2 (Free / Pro).
* Credentials: https://www.deepl.com/pro-api
*/
public class DeepLTranslate implements Translate {
    private static final String BASE_URL_FREE = "https://api-free.deepl.com/v2/translate";
    private static final String BASE_URL_PRO = "https://api.deepl.com/v2/translate";
    private static final String USER_AGENT = "Piko/3.8 (morphe)";

    private static final Set<String> supportedTargetLangs = new HashSet<>(Arrays.asList(
            "BG", "CS", "DA", "DE", "EL", "ES", "ET", "FI", "FR",
            "HU", "ID", "IT", "JA", "KO", "LT", "LV", "NB", "NL",
            "PL", "RO", "RU", "SK", "SL", "SV", "TR", "UK"
    ));

    @Override
    public String getProviderName() {
        return "DeepL";
    }

    @Override
    public void translate(final Long tweetId, final String query, final String toLang, final TranslationCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private Exception error = null;

            @Override
            protected String doInBackground(Void... voids) {
                try {
                    String apiKey = Pref.deeplApiKey();
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new Exception(str("piko_native_translator_deepl_no_key"));
                    }
                    String targetLang = normalizeTargetLang(toLang);
                    String payload = new JSONObject()
                            .put("text", new JSONArray().put(query))
                            .put("target_lang", targetLang)
                            .toString();

                    String body = post(BASE_URL_FREE, apiKey, payload);
                    if (body == null) {
                        // Free endpoint rejected explicitly (e.g. pro key); fall back to the pro endpoint.
                        body = post(BASE_URL_PRO, apiKey, payload);
                        if (body == null) {
                            throw new Exception(str("translate_tweet_error"));
                        }
                    }
                    return parseTranslationResponse(body);
                } catch (Exception e) {
                    error = e;
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String result) {
                if (error != null) {
                    callback.onError(error);
                } else {
                    callback.onTranslationComplete(result);
                }
            }
        }.execute();
    }

    /**
     * POSTs the payload to the given DeepL endpoint and returns the response body,
     * or {@code null} when the endpoint refuses the credential (free/pro mismatch).
     */
    private String post(String endpoint, String apiKey, String payload) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "DeepL-Auth-Key " + apiKey);
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK) {
            return read(connection.getInputStream());
        }
        if (code == HttpURLConnection.HTTP_FORBIDDEN || code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        String detail = read(connection.getErrorStream());
        throw new Exception("DeepL HTTP " + code + ": " + (detail == null ? "" : detail));
    }

    private String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    /**
     * Maps a language tag to a DeepL target language code.
     * DeepL rejects bare "EN"/"PT"/"ZH" and several tags, so region-specific
     * variants are resolved before calling.
     */
    private String normalizeTargetLang(String toLang) throws Exception {
        if (toLang == null || toLang.isEmpty()) {
            return "EN-US";
        }
        String upper = toLang.toUpperCase();
        switch (upper) {
            case "EN": return "EN-US";
            case "PT": return "PT-PT";
            case "ZH": return "ZH-HANS";
            default: {
                int dash = upper.indexOf('-');
                String base = dash > 0 ? upper.substring(0, dash) : upper;
                if (supportedTargetLangs.contains(base) || base.equals("EN") || base.equals("ZH") || base.equals("PT")) {
                    return base.equals("EN") ? "EN-US" : base.equals("ZH") ? "ZH-HANS" : base.equals("PT") ? "PT-PT" : base;
                }
                if (upper.equals("EN-GB") || upper.equals("EN-US") || upper.equals("PT-BR") || upper.equals("PT-PT") ||
                        upper.equals("ZH-HANS") || upper.equals("ZH-HANT")) {
                    return upper;
                }
                throw new Exception(str("piko_native_translator_deepl_unsupported_lang", toLang));
            }
        }
    }

    /**
     * Parse the DeepL JSON response.
     * @param jsonResponse Raw JSON response from DeepL
     * @return Translated text
     */
    private String parseTranslationResponse(String jsonResponse) throws Exception {
        JSONObject root = new JSONObject(jsonResponse);
        if (root.has("translations")) {
            JSONArray translations = root.getJSONArray("translations");
            if (translations.length() > 0) {
                return translations.getJSONObject(0).getString("text");
            }
        }
        throw new Exception("translations not found");
    }
}