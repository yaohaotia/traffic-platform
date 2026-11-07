package com.yao.traffic.identity.infrastructure;

import com.yao.traffic.identity.domain.IdentityUser;
import com.yao.traffic.shared.web.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
public class TokenService {
    public static final Duration ACCESS_LIFETIME = Duration.ofMinutes(5);
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;

    public TokenService(JwtEncoder encoder, @Value("${traffic.security.issuer:traffic-platform}") String issuer,
                        @Value("${traffic.security.audience:traffic-web}") String audience) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
    }

    public TokenPair issue(IdentityUser user, RedisSessionStore.Session session, String refreshToken) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(ACCESS_LIFETIME);
        Instant absoluteSeconds = session.absoluteExpiresAt().truncatedTo(ChronoUnit.SECONDS);
        if (expiresAt.isAfter(absoluteSeconds)) expiresAt = absoluteSeconds;
        long expiresIn = Duration.between(now, expiresAt).getSeconds();
        if (expiresIn <= 0) throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "登录会话已过期");
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(Long.toString(user.id())).issuedAt(now).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).claim("sid", session.sid())
                .claim("ver", user.authVersion()).build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenPair(access, refreshToken, expiresIn, session.absoluteExpiresAt());
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn, Instant sessionExpiresAt) { }
}
