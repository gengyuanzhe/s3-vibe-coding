package org.example.unit;

import org.example.auth.AuthState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthStateTest {

    @Test
    void getInstance_shouldReturnSameInstance() {
        AuthState a = AuthState.getInstance();
        AuthState b = AuthState.getInstance();
        assertThat(a).isSameAs(b);
    }

    @Test
    void init_shouldSetAuthMode() {
        AuthState state = AuthState.getInstance();
        state.init("aws-v4");
        assertThat(state.getAuthMode()).isEqualTo("aws-v4");
    }

    @Test
    void setAuthMode_shouldUpdateMode() {
        AuthState state = AuthState.getInstance();
        state.setAuthMode("both");
        assertThat(state.getAuthMode()).isEqualTo("both");
        // reset
        state.setAuthMode("aws-v4");
    }

    @Test
    void init_shouldDefaultToAwsV4_whenNull() {
        AuthState state = AuthState.getInstance();
        state.init(null);
        assertThat(state.getAuthMode()).isEqualTo("aws-v4");
    }
}
