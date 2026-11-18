package com.example.bank.data;

import java.math.BigDecimal;

public record BankCard(
        String cardId,
        String maskedNumber,
        String type,
        String status,
        BigDecimal monthlyLimit,
        BigDecimal spentThisMonth) {
}
