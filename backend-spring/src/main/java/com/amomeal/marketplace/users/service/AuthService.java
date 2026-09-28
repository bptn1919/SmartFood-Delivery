package com.amomeal.marketplace.users.service;

import com.amomeal.marketplace.security.InvalidOrExpiredTokenException;
import com.amomeal.marketplace.security.JwtService;
import com.amomeal.marketplace.security.RefreshTokenService;
import com.amomeal.marketplace.users.dto.*;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserOtp;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.exception.AccountDeactivatedException;
import com.amomeal.marketplace.users.exception.ConfirmPasswordNotMatchException;
import com.amomeal.marketplace.users.exception.EmailAlreadyInUseException;
import com.amomeal.marketplace.users.exception.InvalidOtpException;
import com.amomeal.marketplace.users.exception.PasswordIncorrectException;
import com.amomeal.marketplace.users.exception.UserNotFoundException;
import com.amomeal.marketplace.users.exception.UsernameOrPasswordIncorrectException;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import com.amomeal.marketplace.users.repository.UserOtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Port of {@code ../backend/users/services.py::Service} +
 * {@code ../backend/users/queries.py::Query} — the whole
 * {@code ../backend/users/api.py::AuthenticateAPI} surface except
 * {@code /upgrade-to-chef}, whose request and response payloads are entirely
 * {@code profile} schemas (see {@link #upgradeCustomerToChef}).
 *
 * <p>Check ORDER is confirmed 1:1 against Django (this was an open TODO from the
 * foundation pass): {@code Query.get_user_by_email_and_password} looks the user
 * up by email (miss → 406 USERNAME_OR_PASSWORD_INCORRECT), then verifies the
 * password (miss → the same 406), and only then rejects an inactive account
 * (403 ACCOUNT_DEACTIVATED). That is exactly what {@link #login} does.
 *
 * <p>Per CLAUDE.md §8b every public method that writes owns its own
 * {@code @Transactional} boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final CustomUserRepository customUserRepository;
    private final UserOtpRepository userOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final OtpService otpService;
    private final AuthEmailService authEmailService;
    private final CustomerOnboardingProvider customerOnboardingProvider;

    // =====================================================================
    // Registration / signup
    // =====================================================================

    /**
     * PORT-NOTE: <b>no Django equivalent.</b> This endpoint predates the OTP port
     * (it was the foundation pass's way to get a usable account and token pair).
     * Django's real front door is {@link #signup}, which creates the account
     * INACTIVE and requires an emailed OTP. This one is kept because the
     * {@code attachment}/{@code ingredient}/{@code dish} test suites bootstrap
     * their fixtures through it; it now at least assigns the CUSTOMER role, the
     * way Django's {@code Query.create_user} does. Flagged in PROGRESS.md for
     * removal once fixtures move to signup+verify.
     */
    @Transactional
    public TokenPairResponse register(RegisterRequest request) {
        if (customUserRepository.existsByEmailIgnoreCase(request.email())) {
            throw new EmailAlreadyInUseException();
        }
        CustomUser user = CustomUser.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .phoneNumber(request.phoneNumber())
                .build();
        // Django: Query.create_user unconditionally adds the user to group CUSTOMER.
        user.addRole(UserRole.CUSTOMER);
        user = customUserRepository.save(user);
        return issueTokenPair(user.getId());
    }

    /**
     * Django: {@code Service.signup} → {@code _get_or_create_inactive_user} +
     * {@code _send_signup_otp}.
     *
     * <p>Re-signing-up with an email that exists but is still INACTIVE reuses
     * that row and just re-sends an OTP (so an abandoned signup is recoverable);
     * an already-ACTIVE email is a 409 EMAIL_ALREADY_IN_USE.
     *
     * <p>PORT-NOTE: {@code Query.create_user} derives the username as
     * {@code email.split("@")[0]}, on a column Django also declares unique — so
     * {@code a@x.com} and {@code a@y.com} collide and the second signup dies on an
     * IntegrityError (500). Ported verbatim, including the collision.
     */
    @Transactional
    public OtpSessionResponse signup(SignUpRequest request) {
        if (findByEmail(request.email(), true).isPresent()) {
            throw new EmailAlreadyInUseException();
        }
        CustomUser user = findByEmail(request.email(), false).orElseGet(() -> {
            CustomUser created = CustomUser.builder()
                    .username(request.email().split("@")[0])
                    .email(request.email())
                    .password(passwordEncoder.encode(request.password()))
                    .firstName(request.firstname())
                    .lastName(request.lastname())
                    .phoneNumber(request.phoneNumber())
                    .isActive(false)
                    .build();
            created.addRole(UserRole.CUSTOMER);
            return customUserRepository.save(created);
        });

        return OtpSessionResponse.from(issueOtp(user, OtpPurpose.SIGNUP, null));
    }

    // =====================================================================
    // Session
    // =====================================================================

    /** Django: {@code Service.login}. */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        CustomUser user = customUserRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(UsernameOrPasswordIncorrectException::new);
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new UsernameOrPasswordIncorrectException();
        }
        if (!user.isActive()) {
            throw new AccountDeactivatedException();
        }
        // Django calls django.contrib.auth.login() here, which only matters for its
        // session cookie (this port is stateless, CLAUDE.md §6) — except for the
        // last_login stamp that login() also writes, which IS ported.
        user.setLastLogin(Instant.now());
        customUserRepository.save(user);

        TokenPairResponse tokens = issueTokenPair(user.getId());
        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), toUserResponse(user));
    }

    @Transactional
    public TokenPairResponse refresh(String rawRefreshToken) {
        RefreshTokenService.RotationResult rotated = refreshTokenService.rotate(rawRefreshToken);
        String accessToken = jwtService.issueAccessToken(rotated.userId());
        return new TokenPairResponse(accessToken, rotated.rawRefreshToken());
    }

    /**
     * Django: {@code Query.logout} — a supplied refresh token revokes just that
     * one session; no token revokes every session the user holds (FE-admin's
     * {@code authService.logout} sends no body).
     */
    public boolean logout(CustomUser user, String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revoke(rawRefreshToken);
        } else if (user != null) {
            refreshTokenService.revokeAllForUser(user.getId());
        }
        return true;
    }

    // =====================================================================
    // Profile-adjacent bits that genuinely live in `users`
    // =====================================================================

    /** Django: {@code Service.get_me} — returns the request user unchanged. */
    public UserResponse getMe(CustomUser user) {
        return toUserResponse(user);
    }

    /**
     * Django: {@code Query.update_me} — PORT-NOTE, it assigns the whole
     * {@code full_name} to {@code first_name} and never touches {@code last_name}.
     * Preserved (CLAUDE.md §0.1).
     */
    @Transactional
    public UserResponse updateMe(CustomUser user, String fullName) {
        CustomUser managed = customUserRepository.findById(user.getId()).orElseThrow(UserNotFoundException::new);
        managed.setFirstName(fullName);
        return toUserResponse(customUserRepository.save(managed));
    }

    /**
     * Django: {@code Service.check_is_chef} ({@code users/api.py::check_is_chef}).
     *
     * <p>PORT-NOTE: {@code chef_id} is <b>always null</b>, faithfully. Django
     * guards it with {@code hasattr(request.user, 'chef')}, but no model in the
     * project declares {@code related_name="chef"} — {@code ChefProfile} uses
     * {@code related_name="chef_profile"} — so that branch is dead and the field
     * is always {@code None}. FE-admin's {@code authService.checkIsChef} already
     * tolerates a null {@code chef_id}.
     */
    public CheckChefResponse checkIsChef(CustomUser user) {
        return new CheckChefResponse(user.hasRole(UserRole.CHEF), null);
    }

    /**
     * Django: {@code Query.upgrade_customer_to_chef} — leave the CUSTOMER group,
     * join the CHEF group.
     *
     * <p>PORT-NOTE: the {@code POST /api/auth/upgrade-to-chef} <b>endpoint</b> is
     * deliberately NOT ported here. Its request body is
     * {@code {chef_profile: ChefProfileDetailSchema, chef_payment:
     * ChefPaymentInfoRequest}} and its response is a
     * {@code ChefProfileDetailResponeSchema} — i.e. it is 90% {@code profile}
     * module surface ({@code ProfileService.create_chef_profile} +
     * {@code ChefPaymentService.create_or_update_payment_info}), which is out of
     * scope and out of port order (CLAUDE.md §7). This method is the
     * {@code users}-owned half — the role transition — exposed now so the
     * {@code profile} port can call it inside its own transaction instead of
     * reaching into the role table itself. Django's
     * {@code "Only CUSTOMER can upgrade to CHEF"} guard is a bare
     * {@code raise Exception} (→ 500 CONTACT_ADMIN_FOR_SUPPORT); it is ported as
     * the same non-{@code ApiException} so the status matches.
     */
    @Transactional
    public CustomUser upgradeCustomerToChef(CustomUser user) {
        CustomUser managed = customUserRepository.findById(user.getId()).orElseThrow(UserNotFoundException::new);
        if (!managed.hasRole(UserRole.CUSTOMER)) {
            throw new IllegalStateException("Only CUSTOMER can upgrade to CHEF");
        }
        managed.removeRole(UserRole.CUSTOMER);
        managed.addRole(UserRole.CHEF);
        return customUserRepository.save(managed);
    }

    // =====================================================================
    // Passwords
    // =====================================================================

    /** Django: {@code Service.change_password}. */
    @Transactional
    public boolean changePassword(CustomUser user, PasswordChangeRequest payload) {
        CustomUser managed = customUserRepository.findById(user.getId()).orElseThrow(UserNotFoundException::new);
        if (!passwordEncoder.matches(payload.oldPassword(), managed.getPassword())) {
            throw new PasswordIncorrectException();
        }
        managed.setPassword(passwordEncoder.encode(payload.newPassword()));
        customUserRepository.save(managed);
        // PORT-NOTE: Django does NOT revoke outstanding refresh tokens on a password
        // change, even though users/tokens.py::revoke_all_refresh_tokens documents
        // itself as being "used on password change". Preserved.
        authEmailService.sendPasswordChangedEmail(managed);
        return true;
    }

    /** Django: {@code Service.forget_password} — only ACTIVE accounts can reset. */
    @Transactional
    public OtpSessionResponse forgetPassword(String email) {
        CustomUser user = findByEmail(email, true).orElseThrow(UserNotFoundException::new);
        return OtpSessionResponse.from(issueOtp(user, OtpPurpose.RESET_PASSWORD, null));
    }

    /**
     * Django: {@code Service.reset_password}.
     *
     * <p><b>Deliberate deviation from Django, by explicit user decision.</b> Django's
     * lookup ({@code Query.get_otp_record}) filters {@code active=True}, but the
     * preceding {@code verify_otp} step (the only way to set
     * {@code otp_verified=True}, which this method also requires) sets
     * {@code active=False} in the same save — so in Django this record is always
     * invisible here and the whole password-reset flow can never complete
     * (structurally identical to the {@code restore_ingredient} dead end
     * catalogued in PROGRESS.md, and originally ported bug-compatible for the same
     * reason). Unlike that admin-only dead end, this is FE-admin's main
     * forgot-password UX, so the user asked for it to actually work: the lookup
     * here intentionally omits the {@code active=True} filter (see
     * {@code UserOtpRepository.findFirstByResetSessionToken}) while still requiring
     * {@code otpVerified} and non-expired, so a session token can only be used
     * once verify-otp has actually completed for it.
     */
    @Transactional
    public boolean resetPassword(PasswordNewRequest payload) {
        if (!payload.newPassword().equals(payload.confirmPassword())) {
            throw new ConfirmPasswordNotMatchException();
        }
        UserOtp record = userOtpRepository
                .findFirstByResetSessionToken(payload.resetSessionToken())
                .orElseThrow(InvalidOrExpiredTokenException::new);
        if (!record.isOtpVerified() || record.isExpired()) {
            throw new InvalidOrExpiredTokenException();
        }
        CustomUser user = customUserRepository.findById(record.getUserId())
                .orElseThrow(UserNotFoundException::new);
        user.setPassword(passwordEncoder.encode(payload.newPassword()));
        customUserRepository.save(user);
        // Consume the token: without the active=True filter (see this method's
        // javadoc), `active=false` alone no longer hides the record from a replay
        // of this same request — otpVerified must also be cleared here, or the
        // password could be reset again and again until the OTP's TTL expires.
        // Django never has to do this because its (broken) active=True filter
        // already made the record single-use by accident.
        record.setActive(false);
        record.setOtpVerified(false);
        userOtpRepository.save(record);
        return true;
    }

    // =====================================================================
    // OTP
    // =====================================================================

    /**
     * Django: {@code Service.verify_otp}. The cascade itself lives in
     * {@link OtpService#verify}; the SIGNUP side effect is here.
     *
     * <p>PORT-NOTE: Django also does
     * {@code CustomerProfile.objects.get_or_create(user=user)} at this point.
     * The {@code profile} module is not ported (CLAUDE.md §7), so that row is not
     * created — the {@code profile} port must add it here. The role assignment
     * half ({@code assign_user_to_group(user, CUSTOMER)}) IS ported; it is
     * redundant with signup's own assignment in Django too.
     */
    @Transactional
    public boolean verifyOtp(String resetSessionToken, String otp) {
        UserOtp record = otpService.verify(resetSessionToken, otp);
        if (record.getPurpose() == OtpPurpose.SIGNUP) {
            CustomUser user = customUserRepository.findById(record.getUserId())
                    .orElseThrow(UserNotFoundException::new);
            user.setActive(true);
            user.addRole(UserRole.CUSTOMER);
            customUserRepository.save(user);
        }
        return true;
    }

    /** Django: {@code Service.request_email_change} — the OTP goes to the NEW address. */
    @Transactional
    public OtpSessionResponse requestEmailChange(CustomUser user, String newEmail) {
        Optional<CustomUser> existing = findByEmail(newEmail, true);
        if (existing.isPresent() && !existing.get().getId().equals(user.getId())) {
            throw new EmailAlreadyInUseException();
        }
        return OtpSessionResponse.from(issueOtp(user, OtpPurpose.EMAIL_CHANGE, newEmail));
    }

    /**
     * Django: {@code Service.verify_email_change}.
     *
     * <p><b>PORT-NOTE — deliberate, narrow deviation.</b> Django compares the code
     * with {@code if record.otp != otp}, but {@code UserOTP} has no {@code otp}
     * field at all (only {@code otp_hash}) — so that line raises
     * {@code AttributeError} and the endpoint always returns 500 in Django today.
     * A literal port would mean writing a deliberate crash, so the evident intent
     * is implemented instead: compare against the stored Argon2 hash. Everything
     * around it is faithful, including the check order (record/active/owner →
     * purpose → code → target_email → email-taken) and the fact that this path
     * does <em>not</em> go through {@code UserOTP.verify()}, so it neither counts
     * attempts nor enforces expiry. Flagged in PROGRESS.md.
     */
    @Transactional
    public boolean verifyEmailChange(CustomUser user, String resetSessionToken, String otp) {
        UserOtp record = userOtpRepository.findFirstByResetSessionTokenAndActiveTrue(resetSessionToken)
                .orElseThrow(InvalidOrExpiredTokenException::new);
        if (!record.getUserId().equals(user.getId())) {
            throw new InvalidOrExpiredTokenException();
        }
        if (record.getPurpose() != OtpPurpose.EMAIL_CHANGE) {
            throw new InvalidOrExpiredTokenException();
        }
        if (!otpService.matches(record, otp)) {
            throw new InvalidOtpException();
        }
        if (record.getTargetEmail() == null || record.getTargetEmail().isBlank()) {
            throw new InvalidOrExpiredTokenException();
        }
        Optional<CustomUser> existing = findByEmail(record.getTargetEmail(), true);
        if (existing.isPresent() && !existing.get().getId().equals(user.getId())) {
            throw new EmailAlreadyInUseException();
        }

        CustomUser managed = customUserRepository.findById(user.getId()).orElseThrow(UserNotFoundException::new);
        managed.setEmail(record.getTargetEmail());
        customUserRepository.save(managed);

        record.setActive(false);
        record.setOtpVerified(true);
        userOtpRepository.save(record);
        return true;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Django: {@code Query.inactive_otp_token} then {@code Query.create_otp} then
     * send — the same three lines open every OTP flow.
     */
    private UserOtp issueOtp(CustomUser user, OtpPurpose purpose, String targetEmail) {
        otpService.deactivateAllForUser(user.getId());
        OtpService.CreatedOtp created = otpService.create(user.getId(), purpose, targetEmail);
        authEmailService.sendVerificationEmail(user, created.plainOtp(), purpose, targetEmail);
        return created.record();
    }

    /** Django: {@code Query.get_user_by_email(email, only_active=True)}. */
    private Optional<CustomUser> findByEmail(String email, boolean onlyActive) {
        return customUserRepository.findByEmailIgnoreCase(email)
                .filter(u -> !onlyActive || u.isActive());
    }

    private UserResponse toUserResponse(CustomUser user) {
        return UserResponse.of(user, customerOnboardingProvider.isOnboarded(user.getId()));
    }

    private TokenPairResponse issueTokenPair(Long userId) {
        String accessToken = jwtService.issueAccessToken(userId);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(userId);
        return new TokenPairResponse(accessToken, refresh.rawToken());
    }
}
