package vn.edu.wallet.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.service.TransferService;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final TransferService transfers;
    public TransferController(TransferService transfers) { this.transfers = transfers; }

    @PostMapping
    public ResponseEntity<ApiDtos.TransferView> create(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestBody ApiDtos.TransferCreate request) {
        TransferService.TransferResult result = transfers.transfer(principal.userId(), request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.receipt());
    }

    @GetMapping("/{id}")
    public ApiDtos.TransferView receipt(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
                                        @PathVariable("id") UUID id) {
        return transfers.receiptForUser(id, principal.userId());
    }

    @GetMapping
    public ApiDtos.TransferPage history(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
                                        @RequestParam(name = "accountId", required = false) UUID accountId,
                                        @RequestParam(name = "page", defaultValue = "0") int page,
                                        @RequestParam(name = "size", defaultValue = "20") int size) {
        return transfers.history(principal.userId(), accountId, page, size);
    }
}
