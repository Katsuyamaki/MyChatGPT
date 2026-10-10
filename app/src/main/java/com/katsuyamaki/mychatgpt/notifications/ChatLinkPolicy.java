package com.katsuyamaki.mychatgpt.notifications;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Canonical origin chat links used both when saving a notification and
 * when consuming its Android PendingIntent/in-app history row.
 *
 * Never navigate to the currently open conversation as a fallback: either
 * an explicit validated /c/... (or project /g/.../c/...) URL exists or the
 * notification has no chat target.
 */
public final class ChatLinkPolicy {
    private static final Pattern PATH = Pattern.compile(
            "^/(?:g/[A-Za-z0-9-]{1,128}/)?c/[A-Za-z0-9-]{8,128}/?$");

    private ChatLinkPolicy() {}

    public static String normalize(String input) {
        if (input == null || input.length() > 1200) return null;
        try {
            URI uri = new URI(input);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"chatgpt.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1
                    || uri.getRawUserInfo() != null) return null;
            String path = uri.getRawPath();
            if (path == null || !PATH.matcher(path).matches()) return null;
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            // Drop search/hash parameters: they should not affect conversation
            // identity, navigation, or the deduplicated completion key.
            return "https://chatgpt.com" + path;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean sameChat(String a, String b) {
        String canonical = normalize(a);
        return canonical != null && canonical.equals(normalize(b));
    }
}
