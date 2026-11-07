package com.yao.traffic.identity.application;

import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.*;

@Service
@PreAuthorize("hasAuthority('identity:user:manage')")
public class IdentityAdministration {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final PasswordEncoder passwords;
    public IdentityAdministration(JdbcTemplate jdbc,PlatformTransactionManager manager,PasswordEncoder passwords){this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.passwords=passwords;}
    public PageResult<Map<String,Object>> list(int page,int size){
        var users=jdbc.queryForList("SELECT id,username,enabled,auth_version FROM identity_user ORDER BY id LIMIT ? OFFSET ?",size,PageResult.offset(page,size));
        for(var user:users){long id=((Number)user.get("id")).longValue();user.put("roleIds",jdbc.queryForList("SELECT role_id FROM identity_user_role WHERE user_id=?",Long.class,id));
            user.put("regionCodes",jdbc.queryForList("SELECT r.code FROM identity_region r JOIN identity_user_region u ON u.region_id=r.id WHERE u.user_id=?",String.class,id));}
        return new PageResult<>(users,page,size);
    }
    public Map<String,Object> catalog(){return Map.of("roles",jdbc.queryForList("SELECT id,code FROM identity_role ORDER BY id"),"regions",jdbc.queryForList("SELECT id,code,name FROM identity_region ORDER BY id"),"permissions",jdbc.queryForList("SELECT code FROM identity_permission ORDER BY code"));}
    public Map<String,Object> create(String username,String initialPassword,List<Long> roles,List<String> regions){
        if(username==null||!username.matches("[A-Za-z0-9._-]{3,100}"))throw bad("用户名须为3至100位字母、数字、点、下划线或横线");
        password(initialPassword);String hash=passwords.encode(initialPassword);
        return tx.execute(status->{
            // Administrative writes serialize on the actor; account creation never derives authority from request role names.
            long actor=lockActor();validateAccess(roles,regions);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE username=?",Integer.class,username)!=0)throw conflict();
            var holder=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO identity_user(username,password_hash,enabled,auth_version) VALUES(?,?,TRUE,0)",new String[]{"id"});s.setString(1,username);s.setString(2,hash);return s;},holder);
            long id=Objects.requireNonNull(holder.getKey()).longValue();assign(id,roles,regions);audit(actor,id,"CREATED");return view(id);
        });
    }
    public Map<String,Object> access(long id,List<Long> roles,List<String> regions){return tx.execute(status->{
        long actor=lockActor();if(actor==id)throw bad("请由其他管理员调整本人权限");validateAccess(roles,regions);
        required(id);jdbc.queryForObject("SELECT id FROM identity_user WHERE id=? FOR UPDATE",Long.class,id);
        jdbc.update("DELETE FROM identity_user_role WHERE user_id=?",id);jdbc.update("DELETE FROM identity_user_region WHERE user_id=?",id);assign(id,roles,regions);
        jdbc.update("UPDATE identity_user SET auth_version=auth_version+1 WHERE id=?",id);audit(actor,id,"ACCESS_REPLACED");return view(id);
    });}
    public void resetPassword(long id,String value){password(value);String hash=passwords.encode(value);tx.executeWithoutResult(status->{long actor=lockActor();required(id);
        jdbc.update("UPDATE identity_user SET password_hash=?,auth_version=auth_version+1 WHERE id=?",hash,id);audit(actor,id,"PASSWORD_RESET");});}
    public Map<String,Object> region(String code,String name){
        if(code==null||!code.matches("[A-Z0-9_-]{1,80}")||name==null||name.isBlank()||name.length()>150)throw bad("区域代码或名称不正确");
        return tx.execute(status->{long actor=lockActor();if(jdbc.queryForObject("SELECT COUNT(*) FROM identity_region WHERE code=?",Integer.class,code)!=0)throw conflict();
            jdbc.update("INSERT INTO identity_region(code,name) VALUES(?,?)",code,name);audit(actor,actor,"REGION_CREATED");return Map.of("code",code,"name",name);});
    }
    public Map<String,Object> role(String code,List<String> permissions){
        if(code==null||!code.matches("[A-Z0-9_-]{1,80}")||permissions==null||permissions.isEmpty()||permissions.size()>100)throw bad("角色及权限内容不正确");
        return tx.execute(status->{long actor=lockActor();if(jdbc.queryForObject("SELECT COUNT(*) FROM identity_role WHERE code=?",Integer.class,code)!=0)throw conflict();
            for(String permission:new HashSet<>(permissions))if(jdbc.queryForObject("SELECT COUNT(*) FROM identity_permission WHERE code=?",Integer.class,permission)!=1)throw bad("权限代码不存在");
            jdbc.update("INSERT INTO identity_role(code) VALUES(?)",code);long id=jdbc.queryForObject("SELECT id FROM identity_role WHERE code=?",Long.class,code);
            for(String permission:new HashSet<>(permissions))jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) SELECT ?,id FROM identity_permission WHERE code=?",id,permission);
            audit(actor,id,"ROLE_CREATED");return Map.of("id",id,"code",code);
        });
    }
    private void validateAccess(List<Long> roles,List<String> regions){
        if(roles==null||roles.isEmpty()||roles.size()>20||regions==null||regions.size()>100)throw bad("角色或区域数量不正确");
        for(Long id:new HashSet<>(roles))if(id==null||jdbc.queryForObject("SELECT COUNT(*) FROM identity_role WHERE id=?",Integer.class,id)!=1)throw bad("角色不存在");
        for(String region:new HashSet<>(regions))if(region==null||jdbc.queryForObject("SELECT COUNT(*) FROM identity_region WHERE code=?",Integer.class,region)!=1)throw bad("区域不存在");
    }
    private void assign(long id,List<Long> roles,List<String> regions){for(Long role:new HashSet<>(roles))jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(?,?)",id,role);for(String region:new HashSet<>(regions))jdbc.update("INSERT INTO identity_user_region(user_id,region_id) SELECT ?,id FROM identity_region WHERE code=?",id,region);}
    private long lockActor(){long actor=CurrentActor.profile().userId();jdbc.queryForObject("SELECT id FROM identity_user WHERE id=? FOR UPDATE",Long.class,actor);return actor;}
    private void required(long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE id=?",Integer.class,id)!=1)throw new BusinessException(HttpStatus.NOT_FOUND,"USER_NOT_FOUND","用户不存在");}
    private Map<String,Object> view(long id){return Map.of("id",id,"username",jdbc.queryForObject("SELECT username FROM identity_user WHERE id=?",String.class,id),"roles",jdbc.queryForList("SELECT r.code FROM identity_role r JOIN identity_user_role u ON u.role_id=r.id WHERE u.user_id=?",String.class,id),"regions",jdbc.queryForList("SELECT r.code FROM identity_region r JOIN identity_user_region u ON u.region_id=r.id WHERE u.user_id=?",String.class,id));}
    private void audit(long actor,long target,String operation){jdbc.update("INSERT INTO identity_audit(actor_id,target_id,operation) VALUES(?,?,?)",actor,target,operation);}
    private static void password(String value){if(value==null||value.length()<12||value.length()>128||value.getBytes(StandardCharsets.UTF_8).length>72)throw bad("密码至少12位且UTF-8长度不超过72字节");}
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_IDENTITY_REQUEST",message);}
    private static BusinessException conflict(){return new BusinessException(HttpStatus.CONFLICT,"IDENTITY_CONFLICT","用户名、角色或区域代码已存在");}
}
