package com.amomeal.marketplace.security;

import com.amomeal.marketplace.common.response.ApiErrorResponseWriter;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors ../backend/utils/router/authenticate.py::AuthBear — the single
 * bearer-token check reused across the HTTP API. A missing Authorization
 * header is simply passed through unauthenticated (Spring Security's
 * {@code authorizeHttpRequests} + entry point produce the generic
 * "UNAUTHORIZED" 401 for protected routes); a header that IS present but
 * invalid/expired short-circuits here with the specific
 * "INVALID_OR_EXPIRED_TOKEN" code, same distinction Django makes.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserRepository customUserRepository;
    private final ApiErrorResponseWriter errorResponseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            Long userId = jwtService.decodeAccessToken(token);
            CustomUser user = customUserRepository.findById(userId).orElseThrow(InvalidOrExpiredTokenException::new);

            // PORT-NOTE: Django's AuthBear does not check is_active/is_superuser here — only the
            // login flow does (see exceptions/users.py::AccountDeactivated). Preserved as-is;
            // see CLAUDE.md §6/§9 — a deactivated user's still-valid access token keeps working
            // until it naturally expires. Flag to the user if this needs tightening.
            //
            // Authorities are the user's real Django role memberships (auth Group names
            // CUSTOMER/CHEF/ADMIN — see PROGRESS.md "Part 1 findings"), exposed with the
            // ROLE_ prefix so @PreAuthorize("hasRole('ADMIN')") works. ROLE_STAFF is still
            // emitted for the one Django site that ORs is_staff onto the ADMIN group check
            // (voucher/services/__init__.py), but it is NO LONGER the ADMIN signal: the
            // `ingredient`/`dish` ports' isStaff stand-in has been replaced by ROLE_ADMIN.
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (UserRole role : user.effectiveRoles()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            }
            if (user.isStaff()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_STAFF"));
            }
            if (user.isSuperuser()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_SUPERUSER"));
            }

            var authentication = new UsernamePasswordAuthenticationToken(user, token, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidOrExpiredTokenException ex) {
            errorResponseWriter.write(response, 401, ex.getMessageCode(), ex.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }
}
