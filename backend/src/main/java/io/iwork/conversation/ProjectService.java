package io.iwork.conversation;

import io.iwork.identity.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final JdbcTemplate db;
    private final IdentityService identity;
    private final io.iwork.artifacts.MaterialService materials;
    private final io.iwork.artifacts.DocumentService documents;
    public ProjectService(JdbcTemplate db,IdentityService identity,io.iwork.artifacts.MaterialService materials,io.iwork.artifacts.DocumentService documents) {
        this.db=db; this.identity=identity; this.materials=materials; this.documents=documents;
    }
    public record Project(String id,String name,String instructions,Instant updatedAt) {}
    private List<Project> query(String condition,Object... args) {
        return db.query("SELECT * FROM projects WHERE "+condition,(r,n)->new Project(r.getString("id"),r.getString("name"),r.getString("instructions"),r.getTimestamp("updated_at").toInstant()),args);
    }
    private void write(Caller caller) {
        if(identity.verify(caller,true).mustChangePassword()) throw new ApiFailure(403,"password_change_required");
    }
    public Project get(Caller caller,String id) {
        var rows=query("id=? AND owner_id=?",id,caller.id());
        if(rows.isEmpty()) throw new ApiFailure(404,"not_found");
        return rows.getFirst();
    }
    private void page(int page) { if(page<0 || page>100000) throw new ApiFailure(400,"invalid_pagination"); }
    public TaskService.Page<Project> list(Caller caller,int page) {
        page(page); var rows=query("owner_id=? ORDER BY updated_at DESC,id DESC LIMIT 21 OFFSET ?",caller.id(),page*20);
        return new TaskService.Page<>(rows.stream().limit(20).toList(),rows.size()>20);
    }
    @Transactional public Project save(Caller caller,String id,Map<String,Object> input) {
        if(!Set.of("name","instructions").containsAll(input.keySet())
            || !(input.get("name") instanceof String name) || name.isBlank() || name.length()>120 || name.codePoints().anyMatch(Character::isISOControl)
            || !(input.getOrDefault("instructions","") instanceof String instructions) || instructions.length()>4000 || instructions.indexOf('\0')>=0) throw new ApiFailure(400,"invalid_project");
        write(caller); if(id!=null) get(caller,id);
        String target=id==null?UUID.randomUUID().toString():id;
        if(id==null) db.update("INSERT INTO projects(id,owner_id,name,instructions) VALUES(?,?,?,?)",target,caller.id(),name.strip(),instructions.strip());
        else db.update("UPDATE projects SET name=?,instructions=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=?",name.strip(),instructions.strip(),target,caller.id());
        return get(caller,target);
    }
    @Transactional public void delete(Caller caller,String id) {
        write(caller); get(caller,id); db.update("DELETE FROM projects WHERE id=? AND owner_id=?",id,caller.id());
    }
    public TaskService.Page<Map<String,Object>> conversations(Caller caller,String id,int page) {
        page(page); get(caller,id);
        var rows=db.queryForList("SELECT id,title,updated_at AS updatedAt,project_id AS projectId FROM conversations WHERE owner_id=? AND project_id=? ORDER BY updated_at DESC,id DESC LIMIT 21 OFFSET ?",caller.id(),id,page*20);
        return new TaskService.Page<>(rows.stream().limit(20).toList(),rows.size()>20);
    }
    @Transactional public void move(Caller caller,String conversation,String project) {
        write(caller);
        if(db.queryForList("SELECT id FROM conversations WHERE id=? AND owner_id=?",String.class,conversation,caller.id()).isEmpty()) throw new ApiFailure(404,"not_found");
        if(project!=null) get(caller,project);
        db.update("UPDATE conversations SET project_id=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=?",project,conversation,caller.id());
    }
    public TaskService.Page<Map<String,Object>> materials(Caller caller,String id,int page) {
        page(page); get(caller,id);
        var rows=db.queryForList("SELECT m.id,m.name,m.format,m.byte_size AS byteSize FROM materials m JOIN project_materials p ON p.material_id=m.id WHERE p.project_id=? AND m.owner_id=? ORDER BY p.created_at DESC,m.id DESC LIMIT 21 OFFSET ?",id,caller.id(),page*20);
        return new TaskService.Page<>(rows.stream().limit(20).toList(),rows.size()>20);
    }
    @Transactional public void addMaterial(Caller caller,String id,String material) {
        write(caller); get(caller,id); materials.get(caller,material);
        db.update("INSERT INTO project_materials(project_id,material_id) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM project_materials WHERE project_id=? AND material_id=?)",id,material,id,material);
    }
    @Transactional public void removeMaterial(Caller caller,String id,String material) {
        write(caller); get(caller,id); db.update("DELETE FROM project_materials WHERE project_id=? AND material_id=?",id,material);
    }
    public TaskService.Page<Map<String,Object>> documents(Caller caller,String id,int page) { get(caller,id); return documents.project(caller,id,page); }
    @Transactional public void addDocument(Caller caller,String id,String version) {
        write(caller); get(caller,id); String document=(String)documents.version(caller,version).get("id");
        db.update("INSERT INTO project_documents(project_id,document_id) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM project_documents WHERE project_id=? AND document_id=?)",id,document,id,document);
    }
    @Transactional public void removeDocument(Caller caller,String id,String document) {
        write(caller); get(caller,id); db.update("DELETE FROM project_documents WHERE project_id=? AND document_id=?",id,document);
    }
}
