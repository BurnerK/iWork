package io.iwork.model;

import io.iwork.identity.Caller;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class ModelController {
    private final ModelService service;

    ModelController(ModelService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/models")
    List<ModelService.Model> list(@AuthenticationPrincipal Caller caller) {
        return service.list(caller);
    }

    @PostMapping("/api/admin/models")
    @ResponseStatus(HttpStatus.CREATED)
    ModelService.Model create(
            @AuthenticationPrincipal Caller caller, @RequestBody Map<String, Object> input) {
        return service.create(caller, input);
    }

    @PutMapping("/api/admin/models/{id}")
    ModelService.Model update(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        return service.update(caller, id, input);
    }

    @GetMapping("/api/admin/model-settings")
    ModelService.Settings settings(@AuthenticationPrincipal Caller caller) {
        return service.settings(caller);
    }

    @PutMapping("/api/admin/model-settings")
    ModelService.Settings settings(
            @AuthenticationPrincipal Caller caller, @RequestBody Map<String, Object> input) {
        return service.settings(caller, input);
    }

    @GetMapping("/api/models")
    List<ModelService.AvailableModel> available(@AuthenticationPrincipal Caller caller) {
        return service.available(caller);
    }

    @GetMapping("/api/admin/users/{id}/model-access")
    List<String> access(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return service.access(caller, id);
    }

    @PutMapping("/api/admin/users/{id}/model-access")
    List<String> access(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        return service.access(caller, id, input);
    }

    @GetMapping("/api/budget")
    ModelService.Budget ownBudget(@AuthenticationPrincipal Caller caller) {
        return service.ownBudget(caller);
    }

    @GetMapping("/api/admin/users/{id}/budget")
    ModelService.Budget readBudget(
            @AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return service.readBudget(caller, id);
    }

    @PutMapping("/api/admin/users/{id}/budget")
    ModelService.Budget saveBudget(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        return service.saveBudget(caller, id, input);
    }
}
