package vn.edu.wallet.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.service.WalletQueries;

@RestController
@RequestMapping("/api/v1")
public class WalletController {
    private final WalletQueries wallets;
    public WalletController(WalletQueries wallets) { this.wallets = wallets; }

    @GetMapping("/me/wallet")
    public ApiDtos.WalletView mine(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal) {
        return wallets.mine(principal.userId());
    }

    @GetMapping("/wallets/lookup/{walletCode}")
    public ApiDtos.WalletLookup lookup(@PathVariable("walletCode") String walletCode) {
        return wallets.lookup(walletCode);
    }
}
