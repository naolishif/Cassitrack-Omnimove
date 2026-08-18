package it.unicas.omnimove.security;

import it.unicas.omnimove.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * US-14: logging out blacklists the JWT, so the API must reject that token
 * afterwards — whether it arrives in the httpOnly cookie or in an
 * Authorization header (an attacker who copied the token before logout).
 */
class JwtFilterBlacklistTest {

    private static final String COOKIE_NAME = "omnimove_jwt";
    private static final String TOKEN = "a.valid.jwt";
    private static final String EMAIL = "traveller@example.com";

    private JwtUtil jwtUtil;
    private UserDetailsService userDetailsService;
    private TokenBlacklistService blacklist;
    private JwtFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        userDetailsService = mock(UserDetailsService.class);
        blacklist = mock(TokenBlacklistService.class);
        chain = mock(FilterChain.class);
        filter = new JwtFilter(jwtUtil, userDetailsService, blacklist);

        when(jwtUtil.isValid(TOKEN)).thenReturn(true);
        when(jwtUtil.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(new User(EMAIL, "irrelevant", Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void filterRequestWithCookie() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(COOKIE_NAME, TOKEN));
        filter.doFilter(req, new MockHttpServletResponse(), chain);
    }

    @Test
    @DisplayName("a live token in the cookie authenticates the request")
    void liveTokenAuthenticates() throws Exception {
        when(blacklist.isBlacklisted(anyString())).thenReturn(false);

        filterRequestWithCookie();

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("baseline: without the blacklist the same token would authenticate")
                .isNotNull();
    }

    @Test
    @DisplayName("a blacklisted token in the cookie is rejected")
    void blacklistedCookieTokenIsRejected() throws Exception {
        when(blacklist.isBlacklisted(TOKEN)).thenReturn(true);

        filterRequestWithCookie();

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("token revoked at logout must not authenticate anyone")
                .isNull();
    }

    @Test
    @DisplayName("a blacklisted token replayed in the Authorization header is rejected too")
    void blacklistedHeaderTokenIsRejected() throws Exception {
        when(blacklist.isBlacklisted(TOKEN)).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + TOKEN);
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the cookie is not the only way in — the header path shares the check")
                .isNull();
    }
}
