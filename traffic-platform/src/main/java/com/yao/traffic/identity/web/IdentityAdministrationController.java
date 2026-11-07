package com.yao.traffic.identity.web;

import com.yao.traffic.identity.application.IdentityAdministration;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/identity")
public class IdentityAdministrationController {
    private final IdentityAdministration service;
    public IdentityAdministrationController(IdentityAdministration service){this.service=service;}
    @GetMapping("/users") public PageResult<Map<String,Object>> users(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return service.list(page,size);}
    @GetMapping("/catalog") public Map<String,Object> catalog(){return service.catalog();}
    @PostMapping("/users") public Map<String,Object> create(@RequestBody User input){return service.create(input.username(),input.initialPassword(),input.roleIds(),input.regionCodes());}
    @PutMapping("/users/{id}/access") public Map<String,Object> access(@PathVariable long id,@RequestBody Access input){return service.access(id,input.roleIds(),input.regionCodes());}
    @PutMapping("/users/{id}/password") public void reset(@PathVariable long id,@RequestBody Password input){service.resetPassword(id,input.password());}
    @PostMapping("/regions") public Map<String,Object> region(@RequestBody Region input){return service.region(input.code(),input.name());}
    @PostMapping("/roles") public Map<String,Object> role(@RequestBody Role input){return service.role(input.code(),input.permissionCodes());}
    public record User(String username,String initialPassword,List<Long> roleIds,List<String> regionCodes){}
    public record Access(List<Long> roleIds,List<String> regionCodes){}
    public record Password(String password){}
    public record Region(String code,String name){}
    public record Role(String code,List<String> permissionCodes){}
}
