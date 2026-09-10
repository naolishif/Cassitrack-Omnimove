package it.unicas.cassitrack.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address the security audit trail and the manager access history record.
 * Two things have to hold at once: behind our own proxy the visitor's real
 * address must come through, and a caller who sends X-Forwarded-For himself
 * must not be able to choose what gets written down against him.
 */
class ClientIpTest {

    private static final String BRIDGE = "172.18.0.1";   // what Nginx looks like to the container
    private static final String CLIENT = "203.0.113.7";

    private static MockHttpServletRequest from(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    @Test
    @DisplayName("behind the proxy, the forwarded address wins over the bridge")
    void forwardedAddressIsUsed() {
        assertThat(ClientIp.of(from(BRIDGE, CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("a hop invented by the caller is discarded, the one Nginx appended is kept")
    void spoofedLeadingHopIsIgnored() {
        // Nginx with $proxy_add_x_forwarded_for appends what it sees to what arrived,
        // so the entry on the right is the only one the caller could not write
        assertThat(ClientIp.of(from(BRIDGE, "1.2.3.4, " + CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("a chain of our own proxies is walked back to the client")
    void proxyChainIsWalked() {
        assertThat(ClientIp.of(from("127.0.0.1", CLIENT + ", 10.0.0.5, 172.20.0.1")))
                .isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("a caller we speak to directly cannot forge the header")
    void headerIgnoredOnDirectConnection() {
        assertThat(ClientIp.of(from("198.51.100.9", "1.2.3.4"))).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("X-Real-IP is honoured when Nginx sets that one instead")
    void realIpHeaderIsHonoured() {
        MockHttpServletRequest request = from(BRIDGE, null);
        request.addHeader("X-Real-IP", CLIENT);
        assertThat(ClientIp.of(request)).isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("with no forwarding headers the peer is still the best answer")
    void fallsBackToPeer() {
        assertThat(ClientIp.of(from(BRIDGE, null))).isEqualTo(BRIDGE);
        assertThat(ClientIp.of(from(BRIDGE, "   "))).isEqualTo(BRIDGE);
        assertThat(ClientIp.of(null)).isNull();
    }

    @Test
    @DisplayName("a client on the LAN survives a chain that is private end to end")
    void privateClientBehindProxy() {
        assertThat(ClientIp.of(from(BRIDGE, "192.168.1.40, " + BRIDGE)))
                .isEqualTo("192.168.1.40");
    }

    @Test
    @DisplayName("ports and IPv6 brackets are stripped")
    void decorationIsStripped() {
        assertThat(ClientIp.of(from(BRIDGE, CLIENT + ":41234"))).isEqualTo(CLIENT);
        assertThat(ClientIp.of(from("::1", "[2001:db8::1]:443"))).isEqualTo("2001:db8::1");
        assertThat(ClientIp.of(from("::1", "2001:db8::1"))).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("the private range is 172.16-31, not everything starting with 172")
    void privateRangeBoundaries() {
        // skipped as proxies
        assertThat(ClientIp.of(from(BRIDGE, CLIENT + ", 172.16.0.1"))).isEqualTo(CLIENT);
        assertThat(ClientIp.of(from(BRIDGE, CLIENT + ", 172.31.255.1"))).isEqualTo(CLIENT);
        // public addresses that merely look alike, and must be reported as the client
        assertThat(ClientIp.of(from(BRIDGE, "172.15.0.9"))).isEqualTo("172.15.0.9");
        assertThat(ClientIp.of(from(BRIDGE, "172.32.0.9"))).isEqualTo("172.32.0.9");
    }
}
