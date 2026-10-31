package io.github.maurdekye.reelsaver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the direct video file for a public Instagram reel without any login.
 *
 * Instagram serves link-preview crawlers a page (and an embed page) that contains the
 * progressive MP4 URL. We ask for those pages the way a crawler would and pull the URL out.
 * Private-account reels have no such public page, so they fail here and the caller says so.
 */
final class ReelExtractor {

    static final String CRAWLER_UA =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    private static final String APP_UA =
            "Instagram 340.0.0.46.109 Android (34/14; 480dpi; 1080x2400; samsung; SM-S918B; dm3q; qcom; en_US)";

    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern SHORTCODE =
            Pattern.compile("instagram\\.com/(?:[^/\\s?#]+/)?(reels?|p|tv)/([A-Za-z0-9_-]{5,})");
    private static final Pattern VIDEO_URL_JSON =
            Pattern.compile("video_url\\\\*\":\\\\*\"(.+?)\\\\*\"");
    private static final Pattern OG_VIDEO_A =
            Pattern.compile("<meta[^>]+property=\"og:video(?::secure_url)?\"[^>]+content=\"([^\"]+)\"");
    private static final Pattern OG_VIDEO_B =
            Pattern.compile("<meta[^>]+content=\"([^\"]+)\"[^>]+property=\"og:video(?::secure_url)?\"");
    private static final Pattern TW_STREAM =
            Pattern.compile("<meta[^>]+(?:name|property)=\"twitter:player:stream\"[^>]+content=\"([^\"]+)\"");
    private static final Pattern ANY_MP4 =
            Pattern.compile("(https:[^\"\\s]+?\\.mp4[^\"\\s]*)");

    static final class ReelRef {
        final String type; // "reel" or "p" or "tv"
        final String code;

        ReelRef(String type, String code) {
            this.type = type;
            this.code = code;
        }
    }

    /** Thrown with a plain, user-facing message. */
    static final class ReelException extends Exception {
        ReelException(String message) {
            super(message);
        }
    }

    /** Pulls the reel reference out of shared text (a bare link, or text containing one). */
    static ReelRef parseSharedText(String text) throws ReelException, IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new ReelException("No link was shared.");
        }
        Matcher direct = SHORTCODE.matcher(text);
        if (direct.find()) {
            return new ReelRef(normalizeType(direct.group(1)), direct.group(2));
        }
        Matcher urlMatcher = URL_IN_TEXT.matcher(text);
        String url = null;
        while (urlMatcher.find()) {
            String candidate = stripTrailingPunctuation(urlMatcher.group());
            if (candidate.contains("instagram.com")) {
                url = candidate;
                break;
            }
            if (url == null) url = candidate;
        }
        if (url == null) {
            throw new ReelException("No Instagram link found in what was shared.");
        }
        // Shortened/share links: follow redirects once and look at where we land.
        String finalUrl = resolveRedirects(url);
        Matcher after = SHORTCODE.matcher(finalUrl);
        if (after.find()) {
            return new ReelRef(normalizeType(after.group(1)), after.group(2));
        }
        throw new ReelException("That link doesn't look like an Instagram reel.");
    }

    private static String normalizeType(String type) {
        return type.startsWith("reel") ? "reel" : type;
    }

    private static String stripTrailingPunctuation(String url) {
        while (!url.isEmpty() && ".,;:!?)]}\"'".indexOf(url.charAt(url.length() - 1)) >= 0
                && !url.endsWith(")")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith(")") && !url.contains("(")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String resolveRedirects(String urlString) throws IOException {
        HttpURLConnection conn = open(urlString, CRAWLER_UA);
        try {
            conn.setInstanceFollowRedirects(true);
            conn.getResponseCode();
            return conn.getURL().toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Tries the public pages in order and returns the direct MP4 URL.
     * The embed page is smallest and most reliable; the normal page is the fallback.
     */
    static String resolveVideoUrl(ReelRef ref) throws ReelException, IOException {
        String base = "https://www.instagram.com/" + ref.type + "/" + ref.code + "/";
        String[][] attempts = {
                {base + "embed/captioned/", CRAWLER_UA},
                {base + "embed/captioned/", APP_UA},
                {base, CRAWLER_UA},
                {base, APP_UA},
        };
        IOException lastError = null;
        for (String[] attempt : attempts) {
            String html;
            try {
                html = fetchPage(attempt[0], attempt[1]);
            } catch (IOException e) {
                lastError = e;
                continue;
            }
            String videoUrl = parseVideoUrl(html);
            if (videoUrl != null) {
                return videoUrl;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new ReelException(
                "Couldn't get this reel's video. It may be from a private account.");
    }

    static String parseVideoUrl(String html) {
        if (html == null) return null;
        for (Pattern pattern : new Pattern[]{VIDEO_URL_JSON, OG_VIDEO_A, OG_VIDEO_B, TW_STREAM}) {
            Matcher m = pattern.matcher(html);
            if (m.find()) {
                String url = unescape(m.group(1));
                if (url.startsWith("http")) return url;
            }
        }
        // Last resort: any MP4 on the page. Prefer ones that look like the main
        // progressive file (longest URL tends to carry the full signed query).
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = ANY_MP4.matcher(html);
        while (m.find()) {
            String url = unescape(m.group(1));
            if (url.startsWith("http")) seen.add(url);
        }
        if (seen.isEmpty()) return null;
        List<String> candidates = new ArrayList<>(seen);
        for (String url : candidates) {
            if (url.contains("/m86/") || url.contains("/m367/")) return url;
        }
        return candidates.get(0);
    }

    /** The pages escape URLs JSON-style, sometimes twice. Undo it until it settles. */
    static String unescape(String s) {
        for (int i = 0; i < 4; i++) {
            String next = s.replace("\\u0026", "&")
                    .replace("\\u002F", "/")
                    .replace("\\u002f", "/")
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                    .replace("\\\\", "\\");
            if (next.equals(s)) break;
            s = next;
        }
        return s;
    }

    static HttpURLConnection open(String urlString, String ua) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Accept", "text/html,video/*,*/*");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        return conn;
    }

    private static String fetchPage(String urlString, String ua) throws IOException {
        HttpURLConnection conn = open(urlString, ua);
        try {
            int status = conn.getResponseCode();
            InputStream in = status >= 200 && status < 400
                    ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IOException("Instagram returned HTTP " + status);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                    if (sb.length() > 4_000_000) break;
                }
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private ReelExtractor() {}
}
