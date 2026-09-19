package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import iwkms.roomflow.exception.BookingConflictException;
import iwkms.roomflow.exception.HolidayUnavailableException;
import iwkms.roomflow.exception.ResourceNotFoundException;
import iwkms.roomflow.modules.booking.api.dto.BookRoomDto;
import iwkms.roomflow.modules.booking.api.dto.CancelBookingDto;
import iwkms.roomflow.modules.booking.impl.domain.Booking;
import iwkms.roomflow.modules.booking.impl.domain.BookingStatus;
import iwkms.roomflow.modules.booking.impl.domain.Room;
import iwkms.roomflow.modules.booking.impl.repository.BookingRepository;
import iwkms.roomflow.modules.booking.impl.repository.RoomRepository;
import iwkms.roomflow.modules.booking.impl.service.BookingManagementService;
import iwkms.roomflow.modules.integration.holiday.dto.HolidayDto;
import iwkms.roomflow.modules.integration.holiday.service.HolidayService;
import iwkms.roomflow.modules.user.impl.domain.Role;
import iwkms.roomflow.modules.user.impl.domain.User;
import iwkms.roomflow.modules.user.impl.repository.UserRepository;
import java.time.LocalDateTime;
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
class BookingManagementServiceTest {
    @Mock
    private BookingRepository bookings;

    @Mock
    private RoomRepository rooms;

    @Mock
    private UserRepository users;

    @Mock
    private HolidayService holidays;

    private BookingManagementService service;
    private final UUID roomId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final LocalDateTime start = LocalDateTime.of(2030, 6, 3, 10, 0);

    @BeforeEach
    void setUp() {
        service = new BookingManagementService(bookings, rooms, users, holidays);
    }

    private BookRoomDto command() {
        return new BookRoomDto(ownerId, roomId, start, start.plusHours(1));
    }

    @Test
    void confirmsAvailableRoomForTheRequestedOwnerAndTime() {
        Room room = Room.builder().id(roomId).name("Meeting room").build();
        when(holidays.getHolidays(2030, "RU")).thenReturn(List.of());
        when(rooms.findByIdAndActiveTrue(roomId)).thenReturn(Optional.of(room));
        when(bookings.findConflictingBookings(roomId, start, start.plusHours(1)))
                .thenReturn(List.of());
        when(bookings.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Booking result = service.bookRoom(command());
        assertEquals(ownerId, result.getUserId());
        assertEquals(room, result.getRoom());
        assertEquals(start, result.getStartTime());
        assertEquals(start.plusHours(1), result.getEndTime());
        assertEquals(BookingStatus.CONFIRMED, result.getStatus());
        assertNotNull(result.getId());
    }

    @Test
    void holidayRejectsBeforeAnyRoomOrBookingWrite() {
        when(holidays.getHolidays(2030, "RU"))
                .thenReturn(List.of(new HolidayDto(start.toLocalDate(), "Holiday", "Holiday", "RU")));
        assertThrows(BookingConflictException.class, () -> service.bookRoom(command()));
        verifyNoInteractions(rooms, bookings);
    }

    @Test
    void holidayProviderFailureDoesNotConfirmUnverifiedBooking() {
        when(holidays.getHolidays(2030, "RU"))
                .thenThrow(new HolidayUnavailableException(new IllegalStateException("offline")));
        assertThrows(HolidayUnavailableException.class, () -> service.bookRoom(command()));
        verifyNoInteractions(rooms, bookings);
    }

    @Test
    void inactiveOrMissingRoomIsNotBookable() {
        when(holidays.getHolidays(2030, "RU")).thenReturn(List.of());
        when(rooms.findByIdAndActiveTrue(roomId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.bookRoom(command()));
        verifyNoInteractions(bookings);
    }

    @Test
    void overlappingBookingIsRejectedWithoutSaving() {
        when(holidays.getHolidays(2030, "RU")).thenReturn(List.of());
        when(rooms.findByIdAndActiveTrue(roomId))
                .thenReturn(Optional.of(Room.builder().id(roomId).build()));
        when(bookings.findConflictingBookings(roomId, start, start.plusHours(1)))
                .thenReturn(List.of(Booking.builder().build()));
        assertThrows(BookingConflictException.class, () -> service.bookRoom(command()));
        verify(bookings, never()).save(any());
    }

    private Booking existingBooking(UUID actor, Role role) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .status(BookingStatus.CONFIRMED)
                .build();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(users.findById(actor))
                .thenReturn(
                        Optional.of(User.builder().id(actor).roles(Set.of(role)).build()));
        return booking;
    }

    @Test
    void ownerCanCancelOwnBooking() {
        Booking booking = existingBooking(ownerId, Role.ROLE_USER);
        service.cancelBooking(new CancelBookingDto(booking.getId(), ownerId));
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        verify(bookings).save(booking);
    }

    @Test
    void administratorCanCancelAnotherUsersBooking() {
        UUID administrator = UUID.randomUUID();
        Booking booking = existingBooking(administrator, Role.ROLE_ADMIN);
        service.cancelBooking(new CancelBookingDto(booking.getId(), administrator));
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    }

    @Test
    void anotherUserCannotCancelAndOriginalStateRemainsConfirmed() {
        UUID attacker = UUID.randomUUID();
        Booking booking = existingBooking(attacker, Role.ROLE_USER);
        assertThrows(
                AccessDeniedException.class,
                () -> service.cancelBooking(new CancelBookingDto(booking.getId(), attacker)));
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        verify(bookings, never()).save(any());
    }

    @Test
    void missingBookingCannotBeCancelled() {
        UUID missing = UUID.randomUUID();
        when(bookings.findById(missing)).thenReturn(Optional.empty());
        assertThrows(
                ResourceNotFoundException.class, () -> service.cancelBooking(new CancelBookingDto(missing, ownerId)));
        verifyNoInteractions(users);
    }
}
