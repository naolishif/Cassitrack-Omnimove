package it.unicas.cassitrack.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address the request actually came from.
 *
 * The app never speaks to the browser directly: the container publishes its
 * port on 127.0.0.1 and Nginx proxies to it, so {@code getRemoteAddr()} is the
 * docker bridge and the address worth recording is in X-Forwarded-For — which
 * the client can set as well.
 *
 * The header is therefore read <strong>only</strong> when the peer we are
 * talking to is one of our own proxies, and the chain is walked from the right:
 * Nginx appends the address it sees to whatever arrived, so the rightmost
 * entries are the hops we trust and the first untrusted one going left is the
 * earliest hop that cannot have been invented by the caller. Reading the
 * leftmost entry instead — the usual shortcut, and what this code did before —
 * hands the choice of what gets logged to whoever sends the header, which for
 * a security audit trail is the one thing it must not do.
 *
 * "Our own proxies" is loopback plus the private ranges: nothing outside the
 * host can reach the port in the first place, so anything private is the
 * deployment itself. A client genuinely on a private LAN therefore looks like a
 * proxy; that case is handled by falling back to the leftmost entry once the
 * whole chain turns out to be private.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        if (request == null) return null;

        String peer = request.getRemoteAddr();
        if (!isTrustedProxy(peer)) return peer;   // spoken to directly: the header proves nothing

        String forwarded = fromForwardedFor(request.getHeader("X-Forwarded-For"));
        if (forwarded != null) return forwarded;

        // Nginx's other convention, and the only value present when it is
        // configured with $remote_addr rather than $proxy_add_x_forwarded_for
        String realIp = clean(request.getHeader("X-Real-IP"));
        if (realIp != null) return realIp;

        return peer;
    }

    /** Rightmost entry that is not one of our proxies; the leftmost if they all are. */
    private static String fromForwardedFor(String header) {
        if (header == null || header.isBlank()) return null;

        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = clean(hops[i]);
            if (hop != null && !isTrustedProxy(hop)) return hop;
        }
        return clean(hops[0]);
    }

    /** Trims an entry and drops the decoration some proxies add — brackets, port. */
    private static String clean(String value) {
        if (value == null) return null;
        String hop = value.trim();
        if (hop.isEmpty()) return null;

        // "[2001:db8::1]:443" or "[2001:db8::1]"
        if (hop.startsWith("[")) {
            int end = hop.indexOf(']');
            return end > 1 ? hop.substring(1, end) : null;
        }
        // "203.0.113.7:41234" — a lone colon cannot be IPv6, which needs at least two
        int colon = hop.indexOf(':');
        if (colon > 0 && hop.indexOf(':', colon + 1) < 0) hop = hop.substring(0, colon);

        return hop.isEmpty() ? null : hop;
    }

    private static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) return true;   // nothing to trust it against

        String a = ip.trim().toLowerCase();

        if (a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")) return true;
        if (a.startsWith("127.")) return true;
        if (a.startsWith("10.")) return true;
        if (a.startsWith("192.168.")) return true;
        if (a.startsWith("169.254.")) return true;                     // link-local
        if (a.startsWith("fc") || a.startsWith("fd")) return true;     // fc00::/7 unique-local
        if (a.startsWith("fe8") || a.startsWith("fe9")
         || a.startsWith("fea") || a.startsWith("feb")) return true;   // fe80::/10 link-local

        // 172.16.0.0/12 — 172.16 through 172.31, which is where docker sits.
        // 172.32+ and 172.0-15 are public and must not be swallowed by a "172." test.
        if (a.startsWith("172.")) {
            int dot = a.indexOf('.', 4);
            if (dot < 0) return false;
            try {
                int second = Integer.parseInt(a.substring(4, dot));
                return second >= 16 && second <= 31;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return false;
    }
}
