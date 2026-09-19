package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import iwkms.roomflow.exception.HolidayUnavailableException;
import iwkms.roomflow.modules.integration.holiday.config.HolidayApiProperties;
import iwkms.roomflow.modules.integration.holiday.dto.HolidayDto;
import iwkms.roomflow.modules.integration.holiday.service.HolidayGateway;
import iwkms.roomflow.modules.integration.holiday.service.HolidayServiceImpl;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HolidayServiceTest {
    @Mock
    private HolidayGateway gateway;

    private HolidayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HolidayServiceImpl(gateway, new HolidayApiProperties("https://holidays.test", 1000, "RU"));
    }

    @Test
    void normalizesCountryAndUsesDefaultForMissingValues() {
        when(gateway.fetch(2030, "RU")).thenReturn(List.of());
        service.getHolidays(2030, "ru");
        service.getHolidays(2030, null);
        service.getHolidays(2030, " ");
        verify(gateway, times(3)).fetch(2030, "RU");
    }

    @Test
    void matchesExactDateRatherThanAnyHolidayInSameYear() {
        LocalDate date = LocalDate.of(2030, 1, 1);
        when(gateway.fetch(2030, "RU")).thenReturn(List.of(new HolidayDto(date, "Новый год", "New Year", "RU")));
        assertTrue(service.isHoliday(date, "RU"));
        assertFalse(service.isHoliday(date.plusDays(1), "RU"));
    }

    @Test
    void unavailableProviderIsNotConvertedToEmptyHolidayList() {
        when(gateway.fetch(2030, "RU"))
                .thenThrow(new HolidayUnavailableException(new IllegalStateException("offline")));
        assertThrows(HolidayUnavailableException.class, () -> service.isHoliday(LocalDate.of(2030, 1, 1), "RU"));
    }
}
