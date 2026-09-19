package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import iwkms.roomflow.modules.integration.holiday.service.HolidayGateway;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class HolidayGatewayTest {
    private MockRestServiceServer server;
    private HolidayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://holidays.test");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HolidayGateway(builder.build());
    }

    @Test
    void mapsProviderResponseAndBuildsExpectedUrl() {
        server.expect(requestTo("https://holidays.test/api/v3/PublicHolidays/2030/RU"))
                .andRespond(withSuccess(
                        "[{\"date\":\"2030-01-01\",\"localName\":\"Новый год\",\"name\":\"New Year\",\"countryCode\":\"RU\"}]",
                        MediaType.APPLICATION_JSON));
        var result = gateway.fetch(2030, "RU");
        assertEquals(1, result.size());
        assertEquals(LocalDate.of(2030, 1, 1), result.getFirst().date());
        assertEquals("RU", result.getFirst().countryCode());
        assertEquals("Новый год", result.getFirst().localName());
        server.verify();
    }

    @Test
    void emptyProviderResponseProducesNoHolidays() {
        server.expect(requestTo("https://holidays.test/api/v3/PublicHolidays/2030/RU"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertTrue(gateway.fetch(2030, "RU").isEmpty());
        server.verify();
    }

    @Test
    void upstreamFailureIsPropagatedForRetryAndCircuitBreakerAdvice() {
        server.expect(requestTo("https://holidays.test/api/v3/PublicHolidays/2030/RU"))
                .andRespond(withServerError());
        assertThrows(HttpServerErrorException.class, () -> gateway.fetch(2030, "RU"));
        server.verify();
    }
}
