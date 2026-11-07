package com.yao.traffic.identity.application;

import com.yao.traffic.identity.infrastructure.IdentityRepository;
import com.yao.traffic.shared.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final IdentityRepository identities;
    public IdentityService(IdentityRepository identities) { this.identities = identities; }

    @PreAuthorize("hasAuthority('identity:user:manage')")
    @Transactional
    public void updateEnabled(long userId, boolean enabled) {
        if(!enabled&&com.yao.traffic.shared.security.CurrentActor.profile().userId()==userId)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"SELF_DISABLE_DENIED","请由其他管理员停用本人账号");
        if (identities.updateEnabled(userId, enabled) != 1)
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
    }

    @PreAuthorize("hasAuthority('region:read')")
    public IdentityRepository.Region region(String code, long actorUserId, boolean canManageUsers) {
        return identities.regionForUser(code, actorUserId, canManageUsers).orElseThrow(() ->
                new BusinessException(canManageUsers ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN,
                        canManageUsers ? "REGION_NOT_FOUND" : "ACCESS_DENIED", "区域不存在或无访问权限"));
    }
}
