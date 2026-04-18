package org.example.auth;

public class AuthState {

    private static final AuthState INSTANCE = new AuthState();

    private volatile String authMode = "aws-v4";

    private AuthState() {}

    public static AuthState getInstance() {
        return INSTANCE;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        if (authMode == null) {
            throw new IllegalArgumentException("authMode must not be null");
        }
        this.authMode = authMode;
    }

    public void init(String defaultMode) {
        this.authMode = (defaultMode != null) ? defaultMode : "aws-v4";
    }
}
