package io.iwork.conversation;

import io.iwork.identity.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class ProjectController {
    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping("/api/projects")
    TaskService.Page<ProjectService.Project> list(
            @AuthenticationPrincipal Caller caller, @RequestParam(defaultValue = "0") int page) {
        return projects.list(caller, page);
    }

    @GetMapping("/api/projects/{id}")
    ProjectService.Project get(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return projects.get(caller, id);
    }

    @PostMapping("/api/projects")
    @ResponseStatus(HttpStatus.CREATED)
    ProjectService.Project create(
            @AuthenticationPrincipal Caller caller, @RequestBody Map<String, Object> input) {
        return projects.save(caller, null, input);
    }

    @PutMapping("/api/projects/{id}")
    ProjectService.Project update(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        return projects.save(caller, id, input);
    }

    @DeleteMapping("/api/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        projects.delete(caller, id);
    }

    @GetMapping("/api/projects/{id}/conversations")
    TaskService.Page<Map<String, Object>> conversations(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page) {
        return projects.conversations(caller, id, page);
    }

    @PutMapping("/api/conversations/{id}/project")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void move(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        if (!input.keySet().equals(java.util.Set.of("projectId"))
                || input.get("projectId") != null && !(input.get("projectId") instanceof String))
            throw new ApiFailure(400, "invalid_project");
        projects.move(caller, id, (String) input.get("projectId"));
    }

    private String reference(Map<String, Object> input, String key) {
        if (!input.keySet().equals(java.util.Set.of(key)) || !(input.get(key) instanceof String id))
            throw new ApiFailure(400, "invalid_project");
        return id;
    }

    @GetMapping("/api/projects/{id}/materials")
    TaskService.Page<Map<String, Object>> materials(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page) {
        return projects.materials(caller, id, page);
    }

    @PostMapping("/api/projects/{id}/materials")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addMaterial(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        projects.addMaterial(caller, id, reference(input, "materialId"));
    }

    @DeleteMapping("/api/projects/{id}/materials/{material}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMaterial(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @PathVariable String material) {
        projects.removeMaterial(caller, id, material);
    }

    @GetMapping("/api/projects/{id}/documents")
    TaskService.Page<Map<String, Object>> documents(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page) {
        return projects.documents(caller, id, page);
    }

    @PostMapping("/api/projects/{id}/documents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addDocument(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        projects.addDocument(caller, id, reference(input, "versionId"));
    }

    @DeleteMapping("/api/projects/{id}/documents/{document}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeDocument(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @PathVariable String document) {
        projects.removeDocument(caller, id, document);
    }
}
