package com.aijob.analyzer.demo;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoOriginFilterTest {
    private final String origin = "chrome-extension://alcmbaoflidekflnllaapnapjcenfodn";
    @Test void exactExtensionPreflightIncludesAllUsedMethods() throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/api/chat"); request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse(); new DemoOriginFilter(origin).doFilter(request,response,new MockFilterChain());
        assertEquals(204,response.getStatus()); assertEquals(origin,response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("GET,POST,PUT,DELETE,OPTIONS",response.getHeader("Access-Control-Allow-Methods"));
    }
    @Test void unconfiguredBrowserOriginIsRejected() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/jobs");request.addHeader("Origin","https://www.zhipin.com");
        var response = new MockHttpServletResponse();var chain = new MockFilterChain();new DemoOriginFilter(origin).doFilter(request,response,chain);
        assertEquals(403,response.getStatus());assertNull(chain.getRequest());
    }
    @Test void commandLineRequestsWithoutOriginRemainSupported() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/health");var chain = new MockFilterChain();
        new DemoOriginFilter(origin).doFilter(request,new MockHttpServletResponse(),chain);assertSame(request,chain.getRequest());
    }
}
