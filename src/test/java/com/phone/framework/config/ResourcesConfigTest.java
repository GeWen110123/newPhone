package com.phone.framework.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.*;

class ResourcesConfigTest
{
    @Test
    void configuredOriginsAllowCredentialedRequests() throws Exception
    {
        CorsFilter filter = filter(" http://localhost:3000, , http://127.0.0.1:5173 ");
        for (String origin : new String[] {"http://localhost:3000", "http://127.0.0.1:5173"})
        {
            MockHttpServletRequest request = request("GET", origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"));
            assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
            assertSame(request, chain.getRequest());
        }
    }

    @Test
    void preflightAllowsAuthorizationHeaderWithoutCallingApplication() throws Exception
    {
        MockHttpServletRequest request = request("OPTIONS", "http://localhost:3000");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "Authorization, Content-Type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter("http://localhost:3000").doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertEquals("http://localhost:3000", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("POST", response.getHeader("Access-Control-Allow-Methods"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains("Authorization"));
        assertNull(chain.getRequest());
    }

    @Test
    void unconfiguredOriginIsRejected() throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter("http://localhost:3000").doFilter(request("GET", "https://untrusted.example"), response, chain);
        assertEquals(403, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertNull(chain.getRequest());
    }

    @Test
    void explicitlyConfiguredWildcardReturnsConcreteOriginWithoutException() throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter("*").doFilter(request("GET", "http://localhost:5173"), response, new MockFilterChain());
        assertEquals(200, response.getStatus());
        assertEquals("http://localhost:5173", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
    }

    private CorsFilter filter(String origins)
    {
        ResourcesConfig config = new ResourcesConfig();
        ReflectionTestUtils.setField(config, "corsOrigins", origins);
        return config.corsFilter();
    }

    private MockHttpServletRequest request(String method, String origin)
    {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/login");
        request.addHeader("Origin", origin);
        return request;
    }
}
