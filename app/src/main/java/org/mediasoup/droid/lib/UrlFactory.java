package org.mediasoup.droid.lib;

import java.util.Locale;

public class UrlFactory {
    public static String getInvitationLink(String hostname, String roomId, boolean forceH264, boolean forceVP9) {
        String url = String.format(Locale.US, "https://%s/?roomId=%s", hostname, roomId);
        if (forceH264) {
            url += "&forceH264=true";
        } else if (forceVP9) {
            url += "&forceVP9=true";
        }
        return url;
    }

    public static String getProtooUrl(String hostname, int port, String roomId, String peerId, boolean forceH264, boolean forceVP9) {
        String url = String.format(Locale.US, "wss://%s:%d/?roomId=%s&peerId=%s", hostname, port, roomId, peerId);
        if (forceH264) {
            url += "&forceH264=true";
        } else if (forceVP9) {
            url += "&forceVP9=true";
        }
        return url;
    }
}
