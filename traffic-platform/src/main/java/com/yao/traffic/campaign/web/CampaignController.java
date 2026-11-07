package com.yao.traffic.campaign.web;

import com.yao.traffic.campaign.application.CampaignService;
import com.yao.traffic.campaign.domain.Campaign;
import com.yao.traffic.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {
    private final CampaignService campaigns;
    public CampaignController(CampaignService campaigns) { this.campaigns = campaigns; }

    @PostMapping
    public Campaign create(@Valid @RequestBody CreateCampaign request) {
        return campaigns.create(request.title(), request.regionCode(), request.startsAt(), request.endsAt(),
                request.rewardAmount(), request.budgetAmount());
    }

    @PostMapping("/{id}/publish") public Campaign publish(@PathVariable long id) { return campaigns.publish(id); }
    @PostMapping("/{id}/close") public Campaign close(@PathVariable long id) { return campaigns.close(id); }
    @GetMapping public PageResult<Campaign> list(@RequestParam(defaultValue="1") int page,
                                               @RequestParam(defaultValue="20") int size) { return campaigns.list(page, size); }

    public record CreateCampaign(@NotBlank @Size(max=150) String title, @NotBlank @Size(max=80) String regionCode,
                                 @NotNull Instant startsAt, @NotNull Instant endsAt,
                                 @NotNull @DecimalMin("0.01") @Digits(integer=10, fraction=2) BigDecimal rewardAmount,
                                 @NotNull @DecimalMin("0.01") @Digits(integer=10, fraction=2) BigDecimal budgetAmount) { }
}
