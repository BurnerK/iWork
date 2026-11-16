package io.iwork.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import io.iwork.identity.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
class ConversationController {
    private final JdbcTemplate db;
    private final IdentityService identity;
    ConversationController(JdbcTemplate db, IdentityService identity) { this.db=db; this.identity=identity; }
    record Conversation(String id, String title, Instant createdAt, Instant updatedAt) {}
    record Page(List<Conversation> items, int page, int size, boolean hasMore) {}
    private List<Conversation> query(String sql, Object... args) {
        return db.query(sql, (r,n) -> new Conversation(r.getString("id"),r.getString("title"),
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant()),args);
    }
    @GetMapping Page list(@AuthenticationPrincipal Caller caller, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        if(page<0 || page>100000 || size<1 || size>100) throw new ApiFailure(400,"invalid_pagination");
        var rows=query("SELECT * FROM conversations WHERE owner_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",caller.id(),size+1,page*size);
        return new Page(rows.stream().limit(size).toList(),page,size,rows.size()>size);
    }
    @GetMapping("/{id}") Conversation open(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        var rows=query("SELECT * FROM conversations WHERE owner_id=? AND id=?",caller.id(),id);
        if(rows.isEmpty()) throw new ApiFailure(404,"not_found");
        return rows.getFirst();
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    Conversation create(@AuthenticationPrincipal Caller caller, @RequestBody Map<String,Object> input) {
        var live=identity.verify(caller,true);
        if(live.mustChangePassword()) throw new ApiFailure(403,"password_change_required");
        if(!input.keySet().stream().allMatch("title"::equals)) throw new ApiFailure(400,"invalid_input");
        Object supplied=input.getOrDefault("title","新会话");
        if(!(supplied instanceof String title) || title.isBlank() || title.length()>200) throw new ApiFailure(400,"invalid_title");
        var id=UUID.randomUUID().toString();
        db.update("INSERT INTO conversations(id,owner_id,title) VALUES(?,?,?)",id,caller.id(),title.strip());
        return open(caller,id);
    }
}
