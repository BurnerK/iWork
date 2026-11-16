package io.iwork.agent;

import io.iwork.identity.*;
import io.iwork.model.ModelAuthorization;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PersonalAgentService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityService identity;
    private final ModelAuthorization models;
    public PersonalAgentService(JdbcTemplate db,TransactionTemplate tx,IdentityService identity,ModelAuthorization models) {
        this.db=db; this.tx=tx; this.identity=identity; this.models=models;
    }
    public record Agent(String id,String name,String instructions,String modelId,Instant updatedAt) {}
    public record Page(List<Agent> items,boolean hasMore) {}
    private List<Agent> query(String condition,Object... args) {
        return db.query("SELECT * FROM personal_agents WHERE "+condition,(r,n)->new Agent(r.getString("id"),r.getString("name"),r.getString("instructions"),r.getString("model_id"),r.getTimestamp("updated_at").toInstant()),args);
    }
    public Agent get(Caller caller,String id) {
        var rows=query("id=? AND owner_id=?",id,caller.id());
        if(rows.isEmpty()) throw new ApiFailure(404,"not_found");
        return rows.getFirst();
    }
    public Page list(Caller caller,int page) {
        if(page<0 || page>100000) throw new ApiFailure(400,"invalid_pagination");
        var rows=query("owner_id=? ORDER BY updated_at DESC,id DESC LIMIT 21 OFFSET ?",caller.id(),page*20);
        return new Page(rows.stream().limit(20).toList(),rows.size()>20);
    }
    public Agent save(Caller caller,String id,Map<String,Object> input) {
        if(!input.keySet().equals(Set.of("name","instructions","modelId"))
            || !(input.get("name") instanceof String name) || name.isBlank() || name.length()>120 || name.codePoints().anyMatch(Character::isISOControl)
            || !(input.get("instructions") instanceof String instructions) || instructions.isBlank() || instructions.length()>4000 || instructions.indexOf('\0')>=0
            || !(input.get("modelId") instanceof String model) || !model.matches("[a-f0-9-]{36}")) throw new ApiFailure(400,"invalid_agent");
        return tx.execute(status -> {
            var live=identity.verify(caller,true);
            if(live.mustChangePassword()) throw new ApiFailure(403,"password_change_required");
            if(id!=null) get(caller,id);
            models.require(caller.id(),model);
            String target=id==null?UUID.randomUUID().toString():id;
            if(id==null) db.update("INSERT INTO personal_agents(id,owner_id,name,instructions,model_id) VALUES(?,?,?,?,?)",target,caller.id(),name.strip(),instructions.strip(),model);
            else db.update("UPDATE personal_agents SET name=?,instructions=?,model_id=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=?",name.strip(),instructions.strip(),model,target,caller.id());
            return get(caller,target);
        });
    }
}
