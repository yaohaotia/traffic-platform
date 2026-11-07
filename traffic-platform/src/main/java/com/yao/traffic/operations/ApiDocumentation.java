package com.yao.traffic.operations;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class ApiDocumentation {
    @Bean OpenAPI trafficOpenApi(){return new OpenAPI().info(new Info().title("交通线索智能审核与激励平台").version("v1")
            .description("身份来自Bearer；资金以MySQL流水为准；提现仅为模拟。接口文档需operations:read权限。"))
            .components(new Components().addSecuritySchemes("bearer",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearer"));}
}
