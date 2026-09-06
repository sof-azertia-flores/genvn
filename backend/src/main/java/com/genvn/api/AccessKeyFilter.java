package com.genvn.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks {@link AccessGate} on every {@code /api} request. Runs after the CORS filter, so a
 * refused cross-origin call still carries the CORS headers the browser needs to show the
 * 401 to the page instead of a blank network error.
 */
public class AccessKeyFilter extends OncePerRequestFilter {

    static final String ACCESS_PATH = "/api/access";
    private static final Pattern ASSET_FILE = Pattern.compile("/api/assets/([^/]+)/([^/]+)");

    private final AccessGate gate;
    private final ObjectMapper mapper;

    public AccessKeyFilter(AccessGate gate, ObjectMapper mapper) {
        this.gate = gate;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!gate.required()) return true;
        String path = path(request);
        if (!path.startsWith("/api/") && !path.equals("/api")) return true;
        // A preflight cannot carry the key; the CORS filter answers it before we would.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        return ACCESS_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(AccessGate.HEADER);
        if (gate.matches(presented)) {
            chain.doFilter(request, response);
            return;
        }
        Matcher file = ASSET_FILE.matcher(path(request));
        if (file.matches() && "GET".equalsIgnoreCase(request.getMethod())
                && gate.assetTokenMatches(file.group(1), request.getParameter(AccessGate.ASSET_TOKEN_PARAM))) {
            chain.doFilter(request, response);
            return;
        }
        boolean missing = presented == null || presented.isBlank();
        Dtos.ErrorView body = missing
                ? new Dtos.ErrorView("access_key_required", "需要访问密钥才能使用这个服务。")
                : new Dtos.ErrorView("access_key_invalid", "访问密钥不正确。");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), body);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri;
    }
}
