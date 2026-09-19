package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import iwkms.roomflow.config.security.JwtService;
import iwkms.roomflow.exception.UserAlreadyExistsException;
import iwkms.roomflow.modules.user.api.dto.LoginRequestDto;
import iwkms.roomflow.modules.user.api.dto.RegisterRequestDto;
import iwkms.roomflow.modules.user.impl.domain.Role;
import iwkms.roomflow.modules.user.impl.domain.User;
import iwkms.roomflow.modules.user.impl.repository.UserRepository;
import iwkms.roomflow.modules.user.impl.service.AuthService;
import iwkms.roomflow.modules.user.impl.service.RefreshRotationResult;
import iwkms.roomflow.modules.user.impl.service.RefreshTokenService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository users;

    @Mock
    private PasswordEncoder encoder;

    @Mock
    private JwtService jwt;

    @Mock
    private AuthenticationManager authentication;

    @Mock
    private RefreshTokenService refresh;

    private AuthService service;
    private final User user = User.builder()
            .id(UUID.randomUUID())
            .email("user@example.com")
            .roles(Set.of(Role.ROLE_USER))
            .build();

    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, jwt, authentication, refresh);
    }

    @Test
    void registrationHashesPasswordAndAssignsOnlyUserRole() {
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.empty());
        when(encoder.encode("password123")).thenReturn("encoded-password");
        when(jwt.generateAccessToken(any(User.class))).thenReturn("access-token");
        when(refresh.createRefreshToken(any(User.class))).thenReturn("refresh-token");
        var result = service.register(new RegisterRequestDto(user.getEmail(), "password123"));
        var captured = ArgumentCaptor.forClass(User.class);
        verify(users).save(captured.capture());
        assertEquals("encoded-password", captured.getValue().getPassword());
        assertEquals(Set.of(Role.ROLE_USER), captured.getValue().getRoles());
        assertNotNull(captured.getValue().getId());
        assertEquals("access-token", result.accessToken());
        assertEquals("refresh-token", result.refreshToken());
    }

    @Test
    void duplicateRegistrationDoesNotHashSaveOrIssueTokens() {
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        assertThrows(
                UserAlreadyExistsException.class,
                () -> service.register(new RegisterRequestDto(user.getEmail(), "password123")));
        verify(users, never()).save(any());
        verifyNoInteractions(encoder, jwt, refresh);
    }

    @Test
    void failedAuthenticationCannotIssueTokens() {
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("invalid"));
        assertThrows(BadCredentialsException.class, () -> service.login(new LoginRequestDto(user.getEmail(), "wrong")));
        verifyNoInteractions(users, jwt, refresh);
    }

    @Test
    void loginIssuesTokensAfterAuthentication() {
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwt.generateAccessToken(user)).thenReturn("access-token");
        when(refresh.createRefreshToken(user)).thenReturn("refresh-token");
        var result = service.login(new LoginRequestDto(user.getEmail(), "password123"));
        var order = inOrder(authentication, users, jwt, refresh);
        order.verify(authentication).authenticate(any());
        order.verify(users).findByEmail(user.getEmail());
        order.verify(jwt).generateAccessToken(user);
        order.verify(refresh).createRefreshToken(user);
        assertEquals("access-token", result.accessToken());
    }

    @Test
    void refreshedAccessTokenUsesRolesReturnedByRotation() {
        when(refresh.rotate("old-token"))
                .thenReturn(new RefreshRotationResult(user.getEmail(), Set.of(Role.ROLE_ADMIN), "new-token"));
        when(jwt.generateAccessToken(user.getEmail(), Set.of("ROLE_ADMIN"))).thenReturn("new-access");
        var result = service.refresh("old-token");
        assertEquals("new-access", result.accessToken());
        assertEquals("new-token", result.refreshToken());
    }

    @Test
    void logoutRevokesSessionAndMeDoesNotExposePassword() {
        service.logout("current-token");
        verify(refresh).revokeCurrentToken("current-token");
        var profile = service.me(user);
        assertEquals(user.getId(), profile.id());
        assertEquals(user.getEmail(), profile.email());
        assertEquals(Set.of("ROLE_USER"), profile.roles());
    }
}
