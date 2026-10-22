package com.dastakhat.users;

import com.dastakhat.common.RateLimiter;
import com.dastakhat.config.AppProperties;
import com.dastakhat.security.SenderPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/** Sender authentication. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Maximum number of login attempts per IP and window. */
    private static final int LOGIN_ATTEMPTS = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(5);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserRepository users;
    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public AuthService(AuthenticationManager authenticationManager,
                       SecurityContextRepository securityContextRepository,
                       UserRepository users,
                       RateLimiter rateLimiter,
                       AppProperties properties) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** Authenticates a sender and stores the authentication in the HTTP session. */
    public AuthDtos.UserView login(AuthDtos.LoginRequest request, HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        rateLimiter.tryAcquire("login:" + clientIp(httpRequest), LOGIN_ATTEMPTS, LOGIN_WINDOW);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email().trim(), request.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        SenderPrincipal principal = (SenderPrincipal) authentication.getPrincipal();
        return new AuthDtos.UserView(principal.getId(), principal.getEmail(), principal.getName(), "sender");
    }

    /** Ends the session. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        securityContextRepository.saveContext(null, request, response);
        Optional.ofNullable(request.getSession(false)).ifPresent(session -> session.invalidate());
    }

    /** Creates the development sender if it does not exist yet. */
    @Transactional
    public User seedDevelopmentUser() {
        AppProperties.Seed seed = properties.getSeed();
        if (!seed.isEnabled() || seed.getEmail() == null || seed.getEmail().isBlank()) {
            return null;
        }
        Optional<User> existing = users.findByEmailIgnoreCase(seed.getEmail());
        if (existing.isPresent()) {
            return existing.get();
        }
        User user = new User();
        user.setEmail(seed.getEmail().toLowerCase());
        user.setName(seed.getName());
        user.setPasswordHash(passwordEncoder.encode(seed.getPassword()));
        user.setRole("sender");
        User saved = users.save(user);
        log.info("Created the development sender {}", saved.getEmail());
        return saved;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
