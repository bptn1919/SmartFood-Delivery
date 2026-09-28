package com.amomeal.marketplace.users.web;

import com.amomeal.marketplace.users.dto.*;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Mirrors {@code ../backend/users/api.py::AuthenticateAPI}
 * ({@code @api(prefix_or_class="auth")} → {@code /api/auth/...}). Paths and HTTP
 * methods are cross-checked against {@code FE-admin/src/services/authService.js}
 * and {@code FE-admin/src/utils/constants.js::API_ENDPOINTS.AUTH}.
 *
 * <p>Django declares the controller {@code auth=None} and opts individual
 * endpoints back in with {@code auth=True}. The equivalent split lives in
 * {@code SecurityConfig}: only login/signup/register/refresh/password-forget/
 * verify-otp/password-reset are {@code permitAll}; everything else here requires
 * a bearer token, so {@code @AuthenticationPrincipal CustomUser} is non-null.
 *
 * <p>Response DTOs rely on the global snake_case Jackson strategy
 * (CLAUDE.md §3) — no per-field {@code @JsonProperty} needed.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ---- public ---------------------------------------------------------

    /** PORT-NOTE: no Django equivalent — see {@link AuthService#register}. */
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/register")
    public TokenPairResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /** Django: {@code POST /api/auth/signup}. */
    @PostMapping("/signup")
    public OtpSessionResponse signup(@Valid @RequestBody SignUpRequest request) {
        return authService.signup(request);
    }

    /** Django: {@code POST /api/auth/login}. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Django: {@code POST /api/auth/refresh}. */
    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /** Django: {@code POST /api/auth/password/forget}. */
    @PostMapping("/password/forget")
    public OtpSessionResponse forgetPassword(@Valid @RequestBody PasswordForgetRequest request) {
        return authService.forgetPassword(request.email());
    }

    /** Django: {@code POST /api/auth/verify-otp} — step 2 of signup and of password reset. */
    @PostMapping("/verify-otp")
    public boolean verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return authService.verifyOtp(request.resetSessionToken(), request.otp());
    }

    /** Django: {@code PUT /api/auth/password/reset}. */
    @PutMapping("/password/reset")
    public boolean resetPassword(@Valid @RequestBody PasswordNewRequest request) {
        return authService.resetPassword(request);
    }

    // ---- authenticated --------------------------------------------------

    /** Django: {@code GET /api/auth/me}. */
    @GetMapping("/me")
    public UserResponse getMe(@AuthenticationPrincipal CustomUser user) {
        return authService.getMe(user);
    }

    /** Django: {@code PUT /api/auth/me}. */
    @PutMapping("/me")
    public UserResponse updateMe(@AuthenticationPrincipal CustomUser user,
                                 @Valid @RequestBody UpdateMeRequest request) {
        return authService.updateMe(user, request.fullName());
    }

    /**
     * Django: {@code PUT /api/auth/logout}, body optional. POST is accepted too —
     * this port originally exposed only POST and the other modules' test fixtures
     * plus any early client would break on a straight rename; PUT is the real
     * contract FE-admin calls ({@code api.put('/api/auth/logout')}, no body).
     */
    @RequestMapping(path = "/logout", method = {RequestMethod.PUT, RequestMethod.POST})
    public boolean logout(@AuthenticationPrincipal CustomUser user,
                          @RequestBody(required = false) LogoutRequest request) {
        return authService.logout(user, request == null ? null : request.refreshToken());
    }

    /** Django: {@code PUT /api/auth/password/change}. */
    @PutMapping("/password/change")
    public boolean changePassword(@AuthenticationPrincipal CustomUser user,
                                  @Valid @RequestBody PasswordChangeRequest request) {
        return authService.changePassword(user, request);
    }

    /** Django: {@code POST /api/auth/email-change/request}. */
    @PostMapping("/email-change/request")
    public OtpSessionResponse requestEmailChange(@AuthenticationPrincipal CustomUser user,
                                                 @Valid @RequestBody EmailChangeRequest request) {
        return authService.requestEmailChange(user, request.newEmail());
    }

    /** Django: {@code POST /api/auth/email-change/verify}. */
    @PostMapping("/email-change/verify")
    public boolean verifyEmailChange(@AuthenticationPrincipal CustomUser user,
                                     @Valid @RequestBody VerifyOtpRequest request) {
        return authService.verifyEmailChange(user, request.resetSessionToken(), request.otp());
    }

    /** Django: {@code GET /api/auth/is-chef}. */
    @GetMapping("/is-chef")
    public CheckChefResponse checkIsChef(@AuthenticationPrincipal CustomUser user) {
        return authService.checkIsChef(user);
    }
}
