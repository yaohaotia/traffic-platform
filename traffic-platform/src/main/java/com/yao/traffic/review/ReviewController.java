package com.yao.traffic.review;

import com.yao.traffic.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewTasks tasks;
    public ReviewController(ReviewTasks tasks){this.tasks=tasks;}
    @GetMapping("/review-tasks") public PageResult<ReviewTasks.TaskView> list(@RequestParam(required=false)String status,
            @RequestParam(required=false)String stage,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return tasks.list(status,stage,page,size);}
    @GetMapping("/review-tasks/{id}") public ReviewTasks.TaskView get(@PathVariable String id){return tasks.get(id);}
    @GetMapping("/review-tasks/{id}/evidence") public ResponseEntity<byte[]> evidence(@PathVariable String id){
        var content=tasks.evidenceContent(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mediaType())).contentLength(content.bytes().length)
                .cacheControl(CacheControl.noStore()).header("Content-Disposition",ContentDisposition.inline().filename(content.filename()).build().toString())
                .header("X-Content-Type-Options","nosniff").body(content.bytes());
    }
    @PostMapping("/review-tasks/{id}/claim") public ReviewTasks.ClaimView claim(@PathVariable String id,@Valid @RequestBody Claim input){return tasks.claim(id,input.expectedVersion());}
    @PostMapping("/review-tasks/{id}/decisions") public ReviewTasks.TaskView decide(@PathVariable String id,@Valid @RequestBody Decision input){return tasks.decide(id,input.version(),input.leaseToken(),input.decision(),input.reason());}
    @PostMapping("/review-tasks/{id}/reassign") public ReviewTasks.TaskView reassign(@PathVariable String id,@Valid @RequestBody Reassign input){return tasks.reassign(id,input.expectedVersion(),input.targetReviewerId(),input.reason());}
    @GetMapping("/review-tasks/{id}/audit") public PageResult<ReviewTasks.AuditView> audit(@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return tasks.audits(id,page,size);}
    @PostMapping("/reports/{id}/appeals") public ReviewTasks.AppealView appeal(@PathVariable long id,@Valid @RequestBody Reason input){return tasks.appeal(id,input.reason());}
    public record Claim(@NotNull @PositiveOrZero Long expectedVersion){ }
    public record Decision(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=36)String leaseToken,
                           @NotBlank @Size(max=32)String decision,@NotBlank @Size(max=500)String reason){ }
    public record Reassign(@NotNull @PositiveOrZero Long expectedVersion,@NotNull @Positive Long targetReviewerId,
                           @NotBlank @Size(max=500)String reason){ }
    public record Reason(@NotBlank @Size(max=500)String reason){ }
}
