package com.yao.traffic.reward;

import com.yao.traffic.shared.web.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/management")
@ConditionalOnProperty(prefix="traffic.reward",name="enabled",havingValue="true")
public class RewardOperationsController {
    private final RewardOperations service;
    public RewardOperationsController(RewardOperations service){this.service=service;}
    @GetMapping("/rewards") public PageResult<RewardLedger.Order> orders(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return service.orders(page,size);}
    @GetMapping("/approval-intents") public PageResult<Map<String,Object>> intents(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return service.intents(page,size);}
}
