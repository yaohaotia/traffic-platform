package com.yao.traffic.identity.web;

import com.yao.traffic.identity.application.IdentityService;
import com.yao.traffic.identity.infrastructure.IdentityRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {
    private final IdentityService identities;
    public IdentityController(IdentityService identities) { this.identities = identities; }

    @PutMapping("/users/{id}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enabled(@PathVariable long id, @Valid @RequestBody EnabledRequest input) {
        identities.updateEnabled(id, input.enabled());
    }

    @GetMapping("/regions/{code}")
    public IdentityRepository.Region region(@PathVariable String code, JwtAuthenticationToken authentication) {
        boolean manager = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("identity:user:manage"));
        return identities.region(code, Long.parseLong(authentication.getToken().getSubject()), manager);
    }

    public record EnabledRequest(@NotNull Boolean enabled) { }
}
