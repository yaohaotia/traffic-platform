package com.yao.traffic.campaign.application;

import com.yao.traffic.campaign.domain.Campaign;
import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import com.yao.traffic.operations.CampaignCatalog;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CampaignService {
    public static final RowMapper<Campaign> MAPPER = (rs, row) -> new Campaign(rs.getLong("id"),
            rs.getString("title"), rs.getString("region_code"), rs.getTimestamp("starts_at").toInstant(),
            rs.getTimestamp("ends_at").toInstant(), rs.getBigDecimal("reward_amount"),
            rs.getBigDecimal("budget_amount"), rs.getString("state"));
    private final JdbcTemplate jdbc;
    private final ObjectProvider<CampaignCatalog> catalog;

    public CampaignService(JdbcTemplate jdbc,ObjectProvider<CampaignCatalog> catalog) { this.jdbc = jdbc;this.catalog=catalog; }

    @PreAuthorize("hasAuthority('campaign:manage')")
    @Transactional
    public Campaign create(String title, String region, Instant startsAt, Instant endsAt,
                           BigDecimal reward, BigDecimal budget) {
        if (!startsAt.isBefore(endsAt) || reward.signum() <= 0 || budget.compareTo(reward) < 0)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CAMPAIGN", "任务时间或奖励预算不正确");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM identity_region WHERE code = ?", Integer.class, region) == 0)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_REGION", "区域不存在");
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        long creator = CurrentActor.profile().userId();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO campaign (title, region_code, starts_at, ends_at, reward_amount, budget_amount, state, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                    """, new String[]{"id"});
            statement.setString(1, title);
            statement.setString(2, region);
            statement.setTimestamp(3, Timestamp.from(startsAt));
            statement.setTimestamp(4, Timestamp.from(endsAt));
            statement.setBigDecimal(5, reward);
            statement.setBigDecimal(6, budget);
            statement.setLong(7, creator);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new IllegalStateException("Campaign identity was not generated");
        return required(holder.getKey().longValue(), false);
    }

    @PreAuthorize("hasAuthority('campaign:manage')")
    @Transactional
    public Campaign publish(long id) {
        Campaign campaign = required(id, true);
        if ("PUBLISHED".equals(campaign.state())) return campaign;
        if (!"DRAFT".equals(campaign.state()) || !Instant.now().isBefore(campaign.endsAt()))
            throw new BusinessException(HttpStatus.CONFLICT, "CAMPAIGN_STATE", "任务已结束或已关闭");
        jdbc.update("UPDATE campaign SET state = 'PUBLISHED' WHERE id = ?", id);
        catalog.ifAvailable(cache->cache.invalidateAfterCommit(id));
        return required(id, false);
    }

    @PreAuthorize("hasAuthority('campaign:manage')")
    @Transactional
    public Campaign close(long id) {
        Campaign campaign = required(id, true);
        if (!"CLOSED".equals(campaign.state())) jdbc.update("UPDATE campaign SET state = 'CLOSED' WHERE id = ?", id);
        catalog.ifAvailable(cache->cache.invalidateAfterCommit(id));
        return required(id, false);
    }

    public PageResult<Campaign> list(int page, int size) {
        int offset = PageResult.offset(page, size);
        var actor = CurrentActor.profile();
        if (actor.permissions().contains("campaign:manage"))
            return new PageResult<>(jdbc.query("SELECT * FROM campaign ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, size, offset), page, size);
        if (actor.regionCodes().isEmpty()) return new PageResult<>(List.of(), page, size);
        List<Object> args = new ArrayList<>(actor.regionCodes());
        args.add(size);
        args.add(offset);
        String placeholders = String.join(",", java.util.Collections.nCopies(actor.regionCodes().size(), "?"));
        return new PageResult<>(jdbc.query("SELECT * FROM campaign WHERE state = 'PUBLISHED' AND region_code IN ("
                + placeholders + ") ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, args.toArray()), page, size);
    }

    /** Current read is used by submission transactions as well as campaign state changes. */
    public Campaign required(long id, boolean lock) {
        return jdbc.query("SELECT * FROM campaign WHERE id = ?" + (lock ? " FOR UPDATE" : ""), MAPPER, id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "CAMPAIGN_NOT_FOUND", "征集任务不存在"));
    }
}
