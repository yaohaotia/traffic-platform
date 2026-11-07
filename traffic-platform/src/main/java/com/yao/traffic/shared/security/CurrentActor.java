package com.yao.traffic.shared.security;

import com.yao.traffic.identity.domain.IdentityProfile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Actor identity is always resolved from the verified server-side security context. */
public final class CurrentActor {
    private CurrentActor() { }

    public static IdentityProfile profile() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !(token.getDetails() instanceof IdentityProfile profile))
            throw new AuthenticationCredentialsNotFoundException("Verified identity is required");
        return profile;
    }
}
