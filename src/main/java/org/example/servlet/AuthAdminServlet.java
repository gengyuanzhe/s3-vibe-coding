package org.example.servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.AuthState;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuthAdminServlet extends HttpServlet {

    private static final Set<String> VALID_MODES = Set.of("aws-v4", "both", "none");
    private static final Pattern MODE_PATTERN = Pattern.compile("\"mode\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String mode = AuthState.getInstance().getAuthMode();
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"mode\":\"" + mode + "\"}");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String body = readBody(req);
        String mode = extractMode(body);

        if (mode == null || !VALID_MODES.contains(mode)) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Invalid mode. Must be one of: aws-v4, both, none\"}");
            return;
        }

        AuthState.getInstance().setAuthMode(mode);
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"mode\":\"" + mode + "\"}");
    }

    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String extractMode(String json) {
        if (json == null || json.isEmpty()) return null;
        Matcher matcher = MODE_PATTERN.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
