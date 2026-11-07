package com.yao.traffic.notification;

import com.yao.traffic.shared.web.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@ConditionalOnProperty(prefix="traffic.reward",name="enabled",havingValue="true")
public class NotificationController {
    private final Notifications notifications;
    public NotificationController(Notifications notifications){this.notifications=notifications;}
    @GetMapping public PageResult<Map<String,Object>> own(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return notifications.own(page,size);}
    @PostMapping("/{id}/read") public Map<String,Object> read(@PathVariable String id){return notifications.read(id);}
}
