package io.iwork.model;

import io.iwork.identity.ApiFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ModelAuthorization {
    private final JdbcTemplate db;
    public ModelAuthorization(JdbcTemplate db) { this.db=db; }
    public void require(String userId,String modelId) {
        if(db.queryForObject("SELECT COUNT(*) FROM models m JOIN model_access a ON a.model_id=m.id WHERE a.user_id=? AND m.id=? AND m.enabled=TRUE",
                Integer.class,userId,modelId)==0) throw new ApiFailure(403,"model_not_authorized");
    }
}
