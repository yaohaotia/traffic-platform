package com.yao.traffic.reward;

import com.yao.traffic.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix="traffic.reward",name="enabled",havingValue="true")
public class RewardController {
    private final RewardLedger ledger;
    private final Withdrawals withdrawals;
    private final LedgerReconciliation reconciliation;
    public RewardController(RewardLedger ledger,Withdrawals withdrawals,LedgerReconciliation reconciliation){this.ledger=ledger;this.withdrawals=withdrawals;this.reconciliation=reconciliation;}
    @GetMapping("/wallet") public RewardLedger.Wallet wallet(){return ledger.ownWallet();}
    @GetMapping("/wallet/entries") public PageResult<Map<String,Object>> entries(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ledger.entries(page,size);}
    @GetMapping("/rewards") public PageResult<RewardLedger.Order> orders(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ledger.orders(page,size);}
    @GetMapping("/campaigns/{id}/budget") public RewardLedger.Budget budget(@PathVariable long id){return ledger.budgetView(id);}
    @PostMapping("/campaigns/{id}/budget-additions") public RewardLedger.Budget addBudget(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Addition input){return ledger.addBudget(id,input.amount(),key,input.reason());}
    @PostMapping("/approval-intents/{id}/activate") public RewardLedger.Order activate(@PathVariable String id,@Valid @RequestBody Reason input){return ledger.activate(id,input.reason());}
    @PostMapping("/rewards/{id}/corrections") public RewardLedger.Correction correct(@PathVariable String id,@Valid @RequestBody Reason input){return ledger.correct(id,input.reason());}
    @PostMapping("/withdrawals") public Withdrawals.Withdrawal request(@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Request input){return withdrawals.request(input.amount(),input.regionCode(),key);}
    @GetMapping("/withdrawals") public PageResult<Withdrawals.Withdrawal> own(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return withdrawals.own(page,size);}
    @GetMapping("/management/withdrawals") public PageResult<Withdrawals.Withdrawal> managed(@RequestParam(required=false)String state,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return withdrawals.managed(state,page,size);}
    @PostMapping("/withdrawals/{id}/decisions") public Withdrawals.Withdrawal decide(@PathVariable String id,@Valid @RequestBody Decision input){return withdrawals.decide(id,input.decision(),input.reason());}
    @GetMapping("/campaigns/{id}/reconciliation") public LedgerReconciliation.Check campaignCheck(@PathVariable long id){return reconciliation.campaign(id);}
    @GetMapping("/wallet/reconciliation") public LedgerReconciliation.Check walletCheck(){return reconciliation.ownWallet();}
    public record Addition(@NotNull BigDecimal amount,@NotBlank @Size(max=500)String reason){}
    public record Reason(@NotBlank @Size(max=500)String reason){}
    public record Request(@NotNull BigDecimal amount,@NotBlank @Size(max=80)String regionCode){}
    public record Decision(@NotBlank String decision,@NotBlank @Size(max=500)String reason){}
}
