package com.yao.traffic.migration;

import com.yao.traffic.identity.infrastructure.RedisSessionStore;
import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Explicit historical archive. Old approvals/balances never create new payable rewards. */
@Service
public class LegacyMigration {
    private static final Map<Integer,String> STATES=Map.of(0,"LEGACY_PENDING_DETECTION",1,"LEGACY_RECHECK_REQUIRED",2,"LEGACY_PENDING_REVIEW",3,"LEGACY_APPROVED_NO_SETTLEMENT",4,"LEGACY_REJECTED");
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    public LegacyMigration(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json){this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.json=json;}
    @PreAuthorize("hasAuthority('identity:user:manage')")
    public Preview preview(Bundle bundle){return inspect(bundle);}
    @PreAuthorize("hasAuthority('identity:user:manage')")
    public Batch importArchive(Bundle bundle,Map<String,Long> mappings,String expectedSha256,String reason){
        Preview preview=inspect(bundle);
        if(!preview.canImport()||mappings==null||mappings.size()>1000||reason==null||reason.isBlank()||reason.length()>500)throw bad("迁移预演有错误，或缺少映射和操作原因");
        if(!preview.sourceSha256().equals(expectedSha256))throw conflict("预演内容已改变，请重新核对摘要");
        var sorted=new TreeMap<>(mappings);String mappingHash=RedisSessionStore.hash(json.writeValueAsString(sorted));
        return tx.execute(status->{
            for(var user:bundle.users()){
                Long target=mappings.get(user.legacyId());
                if(target==null||jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE id=?",Integer.class,target)!=1)throw bad("每个历史账号都须显式映射到已创建的新账号，迁移不改变启停状态");
            }
            String id=UUID.randomUUID().toString();
            int inserted=jdbc.update("INSERT IGNORE INTO legacy_import_batch(id,source_hash,mapping_hash,actor_id,reason,user_count,report_count) VALUES(?,?,?,?,?,?,?)",
                    id,preview.sourceSha256(),mappingHash,CurrentActor.profile().userId(),reason,bundle.users().size(),bundle.reports().size());
            if(inserted==0){
                var existing=jdbc.queryForMap("SELECT id,mapping_hash FROM legacy_import_batch WHERE source_hash=? FOR UPDATE",preview.sourceSha256());
                if(!mappingHash.equals(existing.get("mapping_hash")))throw conflict("同一档案已使用其他账号映射导入");
                return new Batch((String)existing.get("id"),preview.sourceSha256(),bundle.users().size(),bundle.reports().size(),"IMPORTED");
            }
            for(var user:bundle.users()){
                var existing=jdbc.queryForList("SELECT user_id FROM legacy_user_mapping WHERE legacy_id=? FOR UPDATE",Long.class,user.legacyId());
                if(existing.isEmpty())jdbc.update("INSERT INTO legacy_user_mapping(legacy_id,user_id,batch_id) VALUES(?,?,?)",user.legacyId(),mappings.get(user.legacyId()),id);
                else if(!existing.getFirst().equals(mappings.get(user.legacyId())))throw conflict("历史账号已经关联其他新账号");
            }
            for(var report:bundle.reports()){
                if(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_report_archive WHERE legacy_id=?",Integer.class,report.legacyId())!=0)throw conflict("历史线索已导入，禁止覆盖原档案");
                jdbc.update("INSERT INTO legacy_report_archive(legacy_id,batch_id,user_id,original_status,mapped_state,location,description) VALUES(?,?,?,?,?,?,?)",
                        report.legacyId(),id,mappings.get(report.userLegacyId()),report.status(),STATES.get(report.status()),report.location(),report.description());
            }
            return new Batch(id,preview.sourceSha256(),bundle.users().size(),bundle.reports().size(),"IMPORTED");
        });
    }
    @PreAuthorize("hasAnyAuthority('report:read','report:submit')")
    public PageResult<Map<String,Object>> own(int page,int size){return new PageResult<>(jdbc.queryForList("SELECT legacy_id,original_status,mapped_state,location,description,created_at FROM legacy_report_archive WHERE user_id=? ORDER BY created_at DESC,legacy_id LIMIT ? OFFSET ?",
            CurrentActor.profile().userId(),size,PageResult.offset(page,size)),page,size);}

    private Preview inspect(Bundle bundle){
        if(bundle==null||bundle.schemaVersion()!=1||bundle.users()==null||bundle.reports()==null||bundle.users().size()>1000||bundle.reports().size()>1000)throw bad("仅接受schemaVersion=1且每批最多1000账号/线索的档案");
        var issues=new ArrayList<String>();var users=new HashSet<String>();var reports=new HashSet<String>();var counts=new TreeMap<String,Integer>();
        for(var user:bundle.users()){
            if(user==null||!id(user.legacyId())||user.username()==null||user.username().isBlank()||user.username().length()>100||!users.add(user.legacyId()))issues.add("历史账号编号或名称缺失、超长或重复");
        }
        for(var report:bundle.reports()){
            if(report==null){issues.add("历史线索不能为空");continue;}
            if(!id(report.legacyId())||!reports.add(report.legacyId()))issues.add("历史线索编号缺失、超长或重复");
            if(!users.contains(report.userLegacyId()))issues.add("历史线索缺少可核对的原账号");
            if(report.status()==null||!STATES.containsKey(report.status()))issues.add("存在未知旧状态，需先人工确认");
            else counts.merge(STATES.get(report.status()),1,Integer::sum);
            if(report.location()==null||report.location().length()>255||report.description()==null||report.description().length()>2000)issues.add("历史地点或说明长度不正确");
        }
        return new Preview(RedisSessionStore.hash(json.writeValueAsString(bundle)),bundle.users().size(),bundle.reports().size(),counts,issues.stream().distinct().limit(100).toList(),issues.isEmpty());
    }
    private static boolean id(String id){return id!=null&&id.matches("[A-Za-z0-9._-]{1,80}");}
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_LEGACY_ARCHIVE",message);}
    private static BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,"LEGACY_IMPORT_CONFLICT",message);}
    public record User(String legacyId,String username){}
    public record Report(String legacyId,String userLegacyId,Integer status,String location,String description){}
    public record Bundle(int schemaVersion,List<User> users,List<Report> reports){}
    public record Preview(String sourceSha256,int userCount,int reportCount,Map<String,Integer> mappedStates,List<String> issues,boolean canImport){}
    public record Batch(String id,String sourceSha256,int userCount,int reportCount,String status){}
}
