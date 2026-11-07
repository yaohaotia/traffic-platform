package com.yao.traffic.detection;

import com.yao.traffic.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DetectionController {
    private final DetectionAdministration administration;
    public DetectionController(DetectionAdministration administration){this.administration=administration;}
    @GetMapping("/detection-tasks/{id}")
    public DetectionAdministration.TaskView get(@PathVariable String id){return administration.get(id);}
    @PostMapping("/detection-tasks/{id}/retry")
    public DetectionAdministration.TaskView retry(@PathVariable String id,@Valid @RequestBody Reason input){return administration.retry(id,input.reason());}
    @GetMapping("/outbox-events")
    public PageResult<DetectionAdministration.OutboxView> outbox(@RequestParam(defaultValue="DEAD")String status,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return administration.outbox(status,page,size);}
    @PostMapping("/outbox-events/{id}/retry")
    public DetectionAdministration.OutboxView retryOutbox(@PathVariable String id,@Valid @RequestBody Reason input){return administration.retryOutbox(id,input.reason());}
    public record Reason(@NotBlank @Size(max=500)String reason){ }
}
