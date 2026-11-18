package com.example.bank.data;

import java.math.BigDecimal;
import java.util.Map;

public record IncomeSummary(BigDecimal total, String currency, int transactionCount, Map<String, BigDecimal> bySource) {
}
