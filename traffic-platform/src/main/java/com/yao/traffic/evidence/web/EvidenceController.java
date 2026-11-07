package com.yao.traffic.evidence.web;

import com.yao.traffic.evidence.application.EvidenceService;
import com.yao.traffic.evidence.domain.Evidence;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {
    private final EvidenceService evidence;
    public EvidenceController(EvidenceService evidence) { this.evidence = evidence; }
    @PostMapping public Evidence.Metadata upload(@RequestParam("image") MultipartFile image) { return evidence.upload(image); }
    @GetMapping("/{id}") public Evidence.Metadata metadata(@PathVariable long id) { return evidence.metadata(id); }
    @GetMapping("/{id}/content") public ResponseEntity<byte[]> content(@PathVariable long id){
        var content=evidence.content(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mediaType())).contentLength(content.bytes().length)
                .cacheControl(CacheControl.noStore()).header("Content-Disposition",ContentDisposition.inline().filename(content.filename()).build().toString())
                .header("X-Content-Type-Options","nosniff").body(content.bytes());
    }
}
