package io.iwork.artifacts;

import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/materials")
class MaterialController {
    private final MaterialService materials;
    MaterialController(MaterialService materials) { this.materials=materials; }
    @GetMapping MaterialService.Page list(@AuthenticationPrincipal Caller caller,@RequestParam(defaultValue="0") int page) { return materials.list(caller,page); }
    @GetMapping("/{id}") MaterialService.Material get(@AuthenticationPrincipal Caller caller,@PathVariable String id) { return materials.get(caller,id); }
    @PostMapping(consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) @ResponseStatus(HttpStatus.CREATED)
    MaterialService.Material upload(@AuthenticationPrincipal Caller caller,@RequestHeader("X-File-Name") String encodedName,HttpServletRequest request) throws IOException {
        final String name;
        try { name=URLDecoder.decode(encodedName,StandardCharsets.UTF_8); }
        catch(IllegalArgumentException failure) { throw new ApiFailure(400,"invalid_file_name"); }
        return materials.upload(caller,name,request.getContentLengthLong(),request.getInputStream());
    }
    @GetMapping("/{id}/content") ResponseEntity<byte[]> content(@AuthenticationPrincipal Caller caller,@PathVariable String id) {
        var material=materials.get(caller,id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(material.name(),StandardCharsets.UTF_8).build().toString())
            .body(materials.content(caller,id));
    }
}
