package com.example.bank.data;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BankTransaction(
        String transactionId,
        String accountId,
        LocalDate date,
        BigDecimal amount,
        String currency,
        String merchant,
        String category,
        String description) {

    public boolean isIncome() {
        return amount != null && amount.signum() > 0;
    }

    public boolean isSpending() {
        return amount != null && amount.signum() < 0;
    }
}
