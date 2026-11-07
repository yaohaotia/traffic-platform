package com.yao.traffic.notification;

import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class Notifications {
    private final JdbcTemplate jdbc;
    public Notifications(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PreAuthorize("hasAuthority('notification:read')")
    public PageResult<Map<String,Object>> own(int page,int size){return new PageResult<>(jdbc.queryForList(
            "SELECT id,source_event_id,title,body,read_at,created_at FROM notification WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            CurrentActor.profile().userId(),size,PageResult.offset(page,size)),page,size);}
    @PreAuthorize("hasAuthority('notification:read')")
    public Map<String,Object> read(String id){
        long user=CurrentActor.profile().userId();
        jdbc.update("UPDATE notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP(6)) WHERE id=? AND user_id=?",id,user);
        var rows=jdbc.queryForList("SELECT id,title,body,read_at FROM notification WHERE id=? AND user_id=?",id,user);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","站内通知不存在");
        return rows.getFirst();
    }
}
