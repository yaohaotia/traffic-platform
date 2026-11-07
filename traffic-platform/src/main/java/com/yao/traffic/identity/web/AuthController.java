package com.yao.traffic.identity.web;

import com.yao.traffic.identity.application.AuthService;
import com.yao.traffic.identity.domain.IdentityProfile;
import com.yao.traffic.identity.infrastructure.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public TokenService.TokenPair login(@Valid @RequestBody LoginRequest input) {
        return auth.login(input.username(), input.password());
    }

    @PostMapping("/refresh")
    public TokenService.TokenPair refresh(@Valid @RequestBody RefreshRequest input) {
        return auth.refresh(input.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(JwtAuthenticationToken authentication) {
        auth.logout(authentication.getToken().getClaimAsString("sid"));
    }

    @GetMapping("/me")
    public IdentityProfile me(JwtAuthenticationToken authentication) {
        return auth.me(Long.parseLong(authentication.getToken().getSubject()));
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 256) String password) { }
    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) { }
}
