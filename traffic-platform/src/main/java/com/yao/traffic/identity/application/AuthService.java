package com.yao.traffic.identity.application;

import com.yao.traffic.identity.domain.IdentityProfile;
import com.yao.traffic.identity.domain.IdentityUser;
import com.yao.traffic.identity.infrastructure.IdentityRepository;
import com.yao.traffic.identity.infrastructure.RedisSessionStore;
import com.yao.traffic.identity.infrastructure.TokenService;
import com.yao.traffic.shared.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final IdentityRepository identities;
    private final RedisSessionStore sessions;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public AuthService(IdentityRepository identities, RedisSessionStore sessions,
                       TokenService tokens, PasswordEncoder passwordEncoder) {
        this.identities = identities;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("not-a-real-user-password");
    }

    public TokenService.TokenPair login(String username, String password) {
        IdentityUser user = identities.findByUsername(username).orElse(null);
        boolean matched;
        try { matched = passwordEncoder.matches(password, user == null ? dummyHash : user.passwordHash()); }
        catch (IllegalArgumentException exception) { matched = false; }
        if (user == null || !matched || !user.enabled())
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码不正确");
        RedisSessionStore.OpenedSession opened = sessions.create(user.id(), user.authVersion());
        return tokens.issue(user, opened.session(), opened.refreshToken());
    }

    public TokenService.TokenPair refresh(String oldRefresh) {
        RedisSessionStore.Session session = sessions.requireActive(sessions.sidFromRefresh(oldRefresh));
        IdentityUser user = identities.findById(session.userId()).orElseThrow(AuthService::invalidIdentity);
        if (!user.enabled() || user.authVersion() != session.authVersion()) throw invalidIdentity();
        String refresh = sessions.rotate(oldRefresh, session);
        return tokens.issue(user, session, refresh);
    }

    public void logout(String sid) { sessions.revoke(sid); }

    public IdentityProfile me(long userId) {
        return identities.profile(identities.findById(userId).orElseThrow(AuthService::invalidIdentity));
    }

    private static BusinessException invalidIdentity() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "登录会话无效或已过期");
    }
}
