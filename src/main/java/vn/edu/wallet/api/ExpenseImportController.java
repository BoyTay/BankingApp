package vn.edu.wallet.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.expense.ExpenseImportService;

@RestController
@RequestMapping("/api/v1/expense-imports")
public class ExpenseImportController {
    private final ExpenseImportService imports;
    public ExpenseImportController(ExpenseImportService imports) { this.imports = imports; }

    @PostMapping
    public ResponseEntity<ApiDtos.ImportView> upload(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestParam(name = "format") String format,
            @RequestParam(name = "requestKey") UUID requestKey,
            @RequestParam(name = "file") MultipartFile file) {
        ExpenseImportService.ImportResult result = imports.importFile(principal.userId(), format, requestKey, file);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.view());
    }
}
