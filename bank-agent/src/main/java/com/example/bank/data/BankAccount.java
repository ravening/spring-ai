package com.example.bank.data;

import java.math.BigDecimal;

public record BankAccount(
        String accountId,
        String name,
        String type,
        String currency,
        BigDecimal balance,
        String iban) {
}
