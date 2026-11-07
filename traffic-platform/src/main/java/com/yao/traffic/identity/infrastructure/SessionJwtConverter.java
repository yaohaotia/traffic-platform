package com.yao.traffic.identity.infrastructure;

import com.yao.traffic.identity.domain.IdentityProfile;
import com.yao.traffic.identity.domain.IdentityUser;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

@Component
public class SessionJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final RedisSessionStore sessions;
    private final IdentityRepository identities;
    public SessionJwtConverter(RedisSessionStore sessions, IdentityRepository identities) {
        this.sessions = sessions;
        this.identities = identities;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            String sid = jwt.getClaimAsString("sid");
            if (sid == null || !UUID.fromString(sid).toString().equals(sid) || userId <= 0)
                throw new BadCredentialsException("Invalid session claims");
            Object versionClaim = jwt.getClaim("ver");
            if (!(versionClaim instanceof Long || versionClaim instanceof Integer)
                    || ((Number) versionClaim).longValue() < 0)
                throw new BadCredentialsException("Invalid token version");
            long version = ((Number) versionClaim).longValue();
            RedisSessionStore.Session session = sessions.requireActive(sid);
            if (session.userId() != userId || session.authVersion() != version
                    || jwt.getExpiresAt() == null || jwt.getExpiresAt().isAfter(session.absoluteExpiresAt()))
                throw new BadCredentialsException("Invalid session claims");
            IdentityUser user = identities.findById(userId)
                    .orElseThrow(() -> new BadCredentialsException("Invalid identity"));
            if (!user.enabled() || user.authVersion() != version)
                throw new BadCredentialsException("Identity disabled or session superseded");
            IdentityProfile profile = identities.profile(user);
            var authorities = new ArrayList<SimpleGrantedAuthority>();
            profile.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            profile.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
            JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, user.username());
            authentication.setDetails(profile);
            return authentication;
        } catch (DependencyUnavailableException exception) {
            throw new AuthenticationDependencyException(exception);
        } catch (BusinessException | IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid session", exception);
        }
    }
}
