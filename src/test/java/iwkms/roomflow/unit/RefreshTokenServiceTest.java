package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import iwkms.roomflow.exception.InvalidRefreshTokenException;
import iwkms.roomflow.exception.RefreshTokenExpiredException;
import iwkms.roomflow.modules.user.impl.domain.RefreshToken;
import iwkms.roomflow.modules.user.impl.domain.Role;
import iwkms.roomflow.modules.user.impl.domain.User;
import iwkms.roomflow.modules.user.impl.repository.RefreshTokenRepository;
import iwkms.roomflow.modules.user.impl.service.RefreshTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {
    @Mock
    private RefreshTokenRepository repository;

    private RefreshTokenService service;
    private final User user = User.builder()
            .id(UUID.randomUUID())
            .email("user@example.com")
            .roles(Set.of(Role.ROLE_USER))
            .build();

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repository);
        ReflectionTestUtils.setField(service, "refreshExpirationMs", 60000L);
    }

    private RefreshToken token() {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .familyId(UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(120))
                .build();
    }

    @Test
    void storesOnlyHashAndReturnsAnUnpredictableRefreshToken() throws Exception {
        Instant before = Instant.now();
        String raw = service.createRefreshToken(user);
        var capture = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(capture.capture());
        RefreshToken stored = capture.getValue();
        assertEquals(43, raw.length());
        assertEquals(
                HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))),
                stored.getTokenHash());
        assertNotEquals(raw, stored.getTokenHash());
        assertNotNull(stored.getFamilyId());
        assertEquals(user, stored.getUser());
        assertTrue(stored.getExpiresAt().isAfter(before.plusSeconds(59)));
        assertNull(stored.getRevokedAt());
    }

    @Test
    void rotationRevokesOldTokenAndKeepsFamilyAndIdentity() {
        RefreshToken previous = token();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(previous));
        var result = service.rotate("old-token");
        var capture = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, times(2)).save(capture.capture());
        RefreshToken next = capture.getAllValues().get(1);
        assertNotNull(previous.getRevokedAt());
        assertEquals(previous.getFamilyId(), next.getFamilyId());
        assertSame(previous, next.getRotatedFrom());
        assertEquals(user.getEmail(), result.userEmail());
        assertEquals(Set.of(Role.ROLE_USER), result.roles());
        assertNotEquals("old-token", result.refreshToken());
    }

    @Test
    void reuseRevokesTheWholeTokenFamilyAndIssuesNothing() {
        RefreshToken stolen = token();
        stolen.setRevokedAt(Instant.now().minusSeconds(5));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(stolen));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("stolen-token"));
        verify(repository).revokeByFamilyId(eq(stolen.getFamilyId()), any(Instant.class));
        verify(repository, never()).save(any());
    }

    @Test
    void expirationRevokesTokenWithoutIssuingReplacement() {
        RefreshToken expired = token();
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
        assertThrows(RefreshTokenExpiredException.class, () -> service.rotate("expired-token"));
        assertNotNull(expired.getRevokedAt());
        verify(repository).save(expired);
    }

    @Test
    void missingOrBlankTokenIsRejectedBeforeRepositoryAccess() {
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(null));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(" "));
        verifyNoInteractions(repository);
    }

    @Test
    void unknownTokenIsRejected() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("unknown"));
        verify(repository, never()).save(any());
    }

    @Test
    void logoutRevokesCurrentTokenAndFamily() {
        RefreshToken current = token();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));
        service.revokeCurrentToken("current");
        assertNotNull(current.getRevokedAt());
        verify(repository).save(current);
        verify(repository).revokeByFamilyId(eq(current.getFamilyId()), any(Instant.class));
    }

    @Test
    void logoutIsIdempotentForAlreadyRevokedOrMissingTokens() {
        service.revokeCurrentToken(null);
        service.revokeCurrentToken(" ");
        verifyNoInteractions(repository);
        RefreshToken current = token();
        current.setRevokedAt(Instant.now().minusSeconds(3));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));
        service.revokeCurrentToken("already-revoked");
        verify(repository, never()).save(any());
        verify(repository).revokeByFamilyId(eq(current.getFamilyId()), any(Instant.class));
    }
}
