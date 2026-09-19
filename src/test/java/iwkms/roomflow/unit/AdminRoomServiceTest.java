package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import iwkms.roomflow.exception.ResourceNotFoundException;
import iwkms.roomflow.modules.booking.api.dto.CreateRoomRequestDto;
import iwkms.roomflow.modules.booking.api.dto.UpdateRoomRequestDto;
import iwkms.roomflow.modules.booking.impl.domain.Room;
import iwkms.roomflow.modules.booking.impl.repository.RoomRepository;
import iwkms.roomflow.modules.booking.impl.service.AdminRoomService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminRoomServiceTest {
    @Mock
    private RoomRepository repository;

    private AdminRoomService service;

    @BeforeEach
    void setUp() {
        service = new AdminRoomService(repository);
    }

    @Test
    void newRoomStartsActiveAndTrimsName() {
        when(repository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.create(new CreateRoomRequestDto("  Комната  ", 2, 12));
        assertEquals("Комната", result.name());
        assertTrue(result.isActive());
        assertEquals(2, result.floor());
        assertEquals(12, result.capacity());
        assertNotNull(result.id());
    }

    @Test
    void updatingRoomPreservesIdentityAndReplacesEditableFields() {
        Room room = Room.builder()
                .id(UUID.randomUUID())
                .name("Old")
                .floor(1)
                .capacity(2)
                .build();
        when(repository.findByIdAndActiveTrue(room.getId())).thenReturn(Optional.of(room));
        when(repository.save(room)).thenReturn(room);
        var result = service.update(room.getId(), new UpdateRoomRequestDto(" New ", 3, 20));
        assertEquals(room.getId(), result.id());
        assertEquals("New", result.name());
        assertEquals(3, result.floor());
        assertEquals(20, result.capacity());
    }

    @Test
    void softDeleteKeepsRoomRecordForExistingBookingHistory() {
        Room room = Room.builder().id(UUID.randomUUID()).active(true).build();
        when(repository.findByIdAndActiveTrue(room.getId())).thenReturn(Optional.of(room));
        service.softDelete(room.getId());
        assertFalse(room.isActive());
        verify(repository).save(room);
        verify(repository, never()).delete(any(Room.class));
    }

    @Test
    void missingOrAlreadyDeletedRoomCannotBeChanged() {
        UUID roomId = UUID.randomUUID();
        when(repository.findByIdAndActiveTrue(roomId)).thenReturn(Optional.empty());
        assertThrows(
                ResourceNotFoundException.class, () -> service.update(roomId, new UpdateRoomRequestDto("Room", 1, 4)));
        assertThrows(ResourceNotFoundException.class, () -> service.softDelete(roomId));
        verify(repository, never()).save(any());
    }
}
