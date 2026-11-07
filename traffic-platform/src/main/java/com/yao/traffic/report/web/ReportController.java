package com.yao.traffic.report.web;

import com.yao.traffic.report.application.ReportService;
import com.yao.traffic.report.domain.ReportView;
import com.yao.traffic.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports = reports; }
    @PostMapping public ReportView submit(@Valid @RequestBody Submission request,
                                          @RequestHeader(name="Idempotency-Key", required=false) String key) {
        return reports.submit(request.campaignId(), request.evidenceId(), request.location(), request.description(), request.captureTime(), key);
    }
    @GetMapping("/{id}") public ReportView get(@PathVariable long id) { return reports.get(id); }
    @GetMapping public PageResult<ReportView> list(@RequestParam(defaultValue="1") int page,
                                                 @RequestParam(defaultValue="20") int size) { return reports.list(page, size); }

    public record Submission(@NotNull @Positive Long campaignId, @NotNull @Positive Long evidenceId,
                             @NotBlank @Size(max=255) String location, @NotBlank @Size(max=2000) String description,
                             @NotNull Instant captureTime) { }
}
