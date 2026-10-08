package vn.edu.wallet.api;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.report.StatementService;

@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {
    private final StatementService statements;
    public StatementController(StatementService statements) { this.statements = statements; }

    @GetMapping
    public ResponseEntity<byte[]> export(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "format", required = false) String format,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) throws IOException {
        StatementService.ExportedFile file = statements.export(principal.userId(), from, to, format, page, size);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()));
        if (file.page() != null) {
            response.header("X-Page", file.page().toString());
            response.header("X-Page-Size", file.size().toString());
            response.header("X-Has-More", Boolean.toString(file.hasMore()));
        }
        return response.body(file.bytes());
    }
}
