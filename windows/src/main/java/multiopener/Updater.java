package multiopener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 检查 GitHub 最新 Release（与安卓版"检查更新"同一接口） */
public class Updater {
    private static final String API_URL =
            "https://api.github.com/repos/helloppe/MultiOpener/releases/latest";
    private static final String RELEASE_URL =
            "https://github.com/helloppe/MultiOpener/releases/latest";

    /** 返回最新 tag_name（如 "v1.2.0"）；失败返回 null */
    public static String fetchLatestTag() {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MultiOpener");
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String body = sb.toString();
            int i = body.indexOf("\"tag_name\":");
            if (i < 0) return null;
            i += "\"tag_name\":".length();
            int j = body.indexOf('"', i + 1);
            if (j < 0) return null;
            return body.substring(i + 1, j);
        } catch (Exception e) {
            return null;
        }
    }

    public static String releaseUrl() {
        return RELEASE_URL;
    }
}
