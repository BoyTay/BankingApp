package vn.edu.wallet.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.expense.ExpenseStatisticsService;

@RestController
@RequestMapping("/api/v1/expense-stats")
public class ExpenseStatsController {
    private final ExpenseStatisticsService stats;
    public ExpenseStatsController(ExpenseStatisticsService stats) { this.stats = stats; }

    @GetMapping
    public ApiDtos.ExpenseStatsView view(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "groupBy", required = false) String groupBy) {
        return stats.stats(principal.userId(), from, to, groupBy);
    }
}
