package io.iwork.agent;

import io.iwork.identity.Caller;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents")
class PersonalAgentController {
    private final PersonalAgentService agents;
    PersonalAgentController(PersonalAgentService agents) { this.agents=agents; }
    @GetMapping PersonalAgentService.Page list(@AuthenticationPrincipal Caller caller,@RequestParam(defaultValue="0") int page) { return agents.list(caller,page); }
    @GetMapping("/{id}") PersonalAgentService.Agent get(@AuthenticationPrincipal Caller caller,@PathVariable String id) { return agents.get(caller,id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    PersonalAgentService.Agent create(@AuthenticationPrincipal Caller caller,@RequestBody Map<String,Object> input) { return agents.save(caller,null,input); }
    @PutMapping("/{id}") PersonalAgentService.Agent update(@AuthenticationPrincipal Caller caller,@PathVariable String id,@RequestBody Map<String,Object> input) { return agents.save(caller,id,input); }
}
