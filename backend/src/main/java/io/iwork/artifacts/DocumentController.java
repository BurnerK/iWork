package io.iwork.artifacts;

import io.iwork.identity.Caller;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class DocumentController {
    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/api/conversations/{id}/documents")
    List<Map<String, Object>> list(
            @AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return documents.list(caller, id);
    }

    @GetMapping("/api/document-versions")
    io.iwork.conversation.TaskService.Page<Map<String, Object>> personal(
            @AuthenticationPrincipal Caller caller, @RequestParam(defaultValue = "0") int page) {
        return documents.personal(caller, page);
    }

    @GetMapping("/api/document-versions/{id}")
    Map<String, Object> preview(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        var result = new LinkedHashMap<>(documents.version(caller, id));
        result.remove("sha256");
        result.put("markdown", new String(documents.content(caller, id), StandardCharsets.UTF_8));
        return result;
    }

    @GetMapping("/api/document-versions/{id}/content")
    ResponseEntity<byte[]> content(
            @AuthenticationPrincipal Caller caller, @PathVariable String id) {
        var version = documents.version(caller, id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(version.get("title") + ".md", StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(documents.content(caller, id));
    }
}
