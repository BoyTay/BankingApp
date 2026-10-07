package vn.edu.wallet.expense;

import java.time.LocalDate;

public record ImportedExpense(int sourceRow, LocalDate spentOn, String description,
                              String category, long amountDong) {}
