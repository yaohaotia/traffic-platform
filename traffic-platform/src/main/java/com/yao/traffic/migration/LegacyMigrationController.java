package com.yao.traffic.migration;

import com.yao.traffic.shared.web.PageResult;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class LegacyMigrationController {
    private final LegacyMigration migration;
    public LegacyMigrationController(LegacyMigration migration){this.migration=migration;}
    @PostMapping("/migration/preview") public LegacyMigration.Preview preview(@RequestBody LegacyMigration.Bundle input){return migration.preview(input);}
    @PostMapping("/migration/import") public LegacyMigration.Batch importArchive(@RequestBody Import input){return migration.importArchive(input.bundle(),input.userMappings(),input.expectedSha256(),input.reason());}
    @GetMapping("/legacy-reports") public PageResult<Map<String,Object>> own(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return migration.own(page,size);}
    public record Import(LegacyMigration.Bundle bundle,Map<String,Long> userMappings,String expectedSha256,String reason){}
}
