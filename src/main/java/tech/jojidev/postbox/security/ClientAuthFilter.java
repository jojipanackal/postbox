package tech.jojidev.postbox.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Arrays;

@Component
public class ClientAuthFilter extends OncePerRequestFilter {
    private final Map<String, String> clients;

    // POSTBOX_CLIENTS="app-a=sha256-hex,app-b=sha256-hex"; never store plaintext tokens here.
    public ClientAuthFilter(@Value("${postbox.clients:}") String configured) {
        if (!configured.isBlank() && !Arrays.stream(configured.split(","))
                .allMatch(s -> s.trim().matches("[A-Za-z0-9_-]{1,100}=[0-9a-fA-F]{64}"))) {
            throw new IllegalArgumentException("Invalid POSTBOX_CLIENTS configuration");
        }
        clients = Arrays.stream(configured.split(","))
                .map(String::trim).filter(s -> s.matches("[A-Za-z0-9_-]{1,100}=[0-9a-fA-F]{64}"))
                .map(s -> s.split("=", 2))
                .collect(Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1].toLowerCase()));
        if (clients.values().stream().distinct().count() != clients.size())
            throw new IllegalArgumentException("Service credentials must be unique per client");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/notifications");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String candidate = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        String digest = sha256(candidate);
        String client = clients.entrySet().stream().filter(entry -> MessageDigest.isEqual(
                entry.getValue().getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII)))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (client == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service credential");
            return;
        }
        request.setAttribute("clientId", client);
        chain.doFilter(request, response);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
