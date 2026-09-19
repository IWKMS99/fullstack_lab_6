package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import iwkms.roomflow.exception.ResourceNotFoundException;
import iwkms.roomflow.modules.user.impl.domain.Role;
import iwkms.roomflow.modules.user.impl.domain.User;
import iwkms.roomflow.modules.user.impl.repository.UserRepository;
import iwkms.roomflow.modules.user.impl.service.AdminUserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {
    @Mock
    private UserRepository users;

    private AdminUserService service;
    private final UUID administrator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminUserService(users);
    }

    @Test
    void administratorCannotRemoveOwnAdminRole() {
        User actor =
                User.builder().id(administrator).roles(Set.of(Role.ROLE_ADMIN)).build();
        when(users.findById(administrator)).thenReturn(Optional.of(actor));
        assertThrows(
                AccessDeniedException.class,
                () -> service.updateUserRole(administrator, administrator, Role.ROLE_USER));
        assertEquals(Set.of(Role.ROLE_ADMIN), actor.getRoles());
    }

    @Test
    void administratorCanChangeAnotherUsersRole() {
        User target = User.builder()
                .id(UUID.randomUUID())
                .email("target@example.com")
                .roles(Set.of(Role.ROLE_USER))
                .build();
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        var result = service.updateUserRole(administrator, target.getId(), Role.ROLE_ADMIN);
        assertEquals(Set.of(Role.ROLE_ADMIN), target.getRoles());
        assertEquals(target.getId(), result.id());
    }

    @Test
    void missingUserCannotReceiveRole() {
        UUID missing = UUID.randomUUID();
        when(users.findById(missing)).thenReturn(Optional.empty());
        assertThrows(
                ResourceNotFoundException.class, () -> service.updateUserRole(administrator, missing, Role.ROLE_ADMIN));
    }

    @Test
    void userListContainsOnlyPublicAdministrativeFields() {
        User target = User.builder()
                .id(UUID.randomUUID())
                .email("target@example.com")
                .password("secret-hash")
                .roles(Set.of(Role.ROLE_USER))
                .build();
        when(users.findAll()).thenReturn(List.of(target));
        var result = service.findAllUsers();
        assertEquals(1, result.size());
        assertEquals(target.getEmail(), result.getFirst().email());
        assertEquals(Set.of(Role.ROLE_USER), result.getFirst().roles());
    }
}
