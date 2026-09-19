package iwkms.roomflow;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.io.Decoders;
import iwkms.roomflow.modules.user.api.dto.LoginRequestDto;
import iwkms.roomflow.modules.user.api.dto.RegisterRequestDto;
import iwkms.roomflow.modules.user.impl.domain.User;
import iwkms.roomflow.modules.user.impl.repository.UserRepository;
import iwkms.roomflow.modules.user.impl.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public class AuthControllerIT {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthService authService;

    @Test
    void configuredPublicOriginCanReachAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", "http://localhost:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }

    @Test
    void untrustedOriginCannotSubmitCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", "https://evil.test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("POST /auth/register: должен успешно зарегистрировать нового пользователя и вернуть refresh cookie")
    void shouldRegisterSuccessfully() throws Exception {
        RegisterRequestDto request = new RegisterRequestDto("newuser@test.com", "password123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        Cookie refreshCookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertNotNull(refreshCookie);
        assertTrue(refreshCookie.isHttpOnly());

        User savedUser = userRepository.findByEmail("newuser@test.com").orElseThrow();
        assertTrue(passwordEncoder.matches("password123", savedUser.getPassword()));
    }

    @Test
    @DisplayName("POST /auth/register: должен вернуть 409 Conflict при регистрации с существующим email")
    void shouldReturnConflictOnDuplicateEmail() throws Exception {
        RegisterRequestDto request = new RegisterRequestDto("duplicate@test.com", "password123");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /auth/login: должен успешно аутентифицировать пользователя и вернуть refresh cookie")
    void shouldLoginSuccessfully() throws Exception {
        RegisterRequestDto registerRequest = new RegisterRequestDto("loginuser@test.com", "password123");
        authService.register(registerRequest);

        LoginRequestDto loginRequest = new LoginRequestDto("loginuser@test.com", "password123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        Cookie refreshCookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertNotNull(refreshCookie);
        assertTrue(refreshCookie.isHttpOnly());
    }

    @Test
    @DisplayName("POST /auth/login: должен вернуть 401 Unauthorized при неверном пароле")
    void shouldReturnUnauthorizedOnBadCredentials() throws Exception {
        RegisterRequestDto registerRequest = new RegisterRequestDto("badpass@test.com", "password123");
        authService.register(registerRequest);

        LoginRequestDto loginRequest = new LoginRequestDto("badpass@test.com", "wrongpassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/login: JWT должен содержать claim roles")
    void shouldContainRolesClaimInJwt() throws Exception {
        RegisterRequestDto registerRequest = new RegisterRequestDto("roles@test.com", "password123");
        authService.register(registerRequest);

        LoginRequestDto loginRequest = new LoginRequestDto("roles@test.com", "password123");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();
        String payload = token.split("\\.")[1];
        byte[] decodedPayload = Decoders.BASE64URL.decode(payload);
        com.fasterxml.jackson.databind.JsonNode claims = objectMapper.readTree(decodedPayload);
        com.fasterxml.jackson.databind.JsonNode roles = claims.get("roles");

        assertTrue(roles.isArray());
        assertTrue(java.util.stream.StreamSupport.stream(roles.spliterator(), false)
                .anyMatch(node -> "ROLE_USER".equals(node.asText())));
    }

    @Test
    @DisplayName("POST /auth/refresh: должен ротировать refresh token и выдавать новый access token")
    void shouldRefreshAndRotateToken() throws Exception {
        RegisterRequestDto registerRequest = new RegisterRequestDto("refresh@test.com", "password123");
        authService.register(registerRequest);

        LoginRequestDto loginRequest = new LoginRequestDto("refresh@test.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie initialRefresh = loginResult.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertNotNull(initialRefresh);

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh").cookie(initialRefresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        Cookie rotatedRefresh = refreshResult.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertNotNull(rotatedRefresh);
        assertNotEquals(initialRefresh.getValue(), rotatedRefresh.getValue());

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(initialRefresh)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/logout: должен отзывать refresh token текущей сессии")
    void shouldLogoutAndBlockFurtherRefresh() throws Exception {
        RegisterRequestDto registerRequest = new RegisterRequestDto("logout@test.com", "password123");
        authService.register(registerRequest);

        LoginRequestDto loginRequest = new LoginRequestDto("logout@test.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertNotNull(refreshCookie);

        mockMvc.perform(post("/api/v1/auth/logout").cookie(refreshCookie)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /auth/me: должен возвращать текущего пользователя с валидным access token")
    void shouldReturnCurrentUserForValidAccessToken() throws Exception {
        RegisterRequestDto registerRequest = new RegisterRequestDto("me@test.com", "password123");
        authService.register(registerRequest);

        LoginRequestDto loginRequest = new LoginRequestDto("me@test.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("token")
                .asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@test.com"))
                .andExpect(jsonPath("$.roles", hasItem("ROLE_USER")));
    }

    @Test
    @DisplayName("GET /auth/me: должен вернуть 401 без access token")
    void shouldReturnUnauthorizedForMeWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }
}
