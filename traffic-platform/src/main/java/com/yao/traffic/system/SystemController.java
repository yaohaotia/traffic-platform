package com.yao.traffic.system;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final ObjectProvider<BuildProperties> buildProperties;

    public SystemController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/info")
    public SystemInfo info() {
        BuildProperties build = buildProperties.getIfAvailable();
        return new SystemInfo("traffic-platform", build == null ? "development" : build.getVersion(), "v1");
    }

    public record SystemInfo(String name, String version, String apiVersion) { }
}
