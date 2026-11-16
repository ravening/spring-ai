package com.example.mcpdemo;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class DemoMcpTools {

    private static final Map<String, Double> FX_RATES = fxRates();

    private static final Map<String, String> INVENTORY = inventory();

    private static final Set<String> EU_COUNTRIES = Set.of(
            "AUSTRIA", "BELGIUM", "CZECHIA", "DENMARK", "FINLAND", "FRANCE", "GERMANY",
            "IRELAND", "ITALY", "NETHERLANDS", "POLAND", "PORTUGAL", "SPAIN", "SWEDEN");

    @McpTool(name = "weather_forecast",
            description = "Get a short weather forecast for a city. Returns one line per day with temperature and conditions.")
    public String weatherForecast(
            @McpToolParam(description = "City name, for example Berlin", required = true) String city,
            @McpToolParam(description = "Number of days between 1 and 5. Defaults to 3.", required = false) Integer days) {

        String normalizedCity = city == null || city.isBlank() ? "Berlin" : city.trim();
        int dayCount = days == null ? 3 : Math.max(1, Math.min(days, 5));
        int seed = normalizedCity.toLowerCase(Locale.ROOT).replace(" ", "").hashCode();
        int baseTemperature = 9 + Math.floorMod(seed, 15);
        String[] conditions = { "sunny", "partly cloudy", "light rain", "windy", "clear" };

        StringBuilder forecast = new StringBuilder("Weather forecast for ").append(normalizedCity).append(":\n");
        for (int day = 0; day < dayCount; day++) {
            int temperature = baseTemperature + day * 2 + Math.floorMod(seed + day * 7, 5) - 2;
            String condition = conditions[Math.floorMod(seed + day * 3, conditions.length)];
            forecast.append("- ")
                    .append(LocalDate.now().plusDays(day))
                    .append(": ")
                    .append(condition)
                    .append(", ")
                    .append(temperature)
                    .append(" C\n");
        }
        return forecast.toString().trim();
    }

    @McpTool(name = "inventory_check",
            description = "Check stock, warehouse and restock date for a product SKU.")
    public String inventoryCheck(
            @McpToolParam(description = "Product SKU such as SKU-1001", required = true) String sku) {

        String key = sku == null ? "" : sku.trim().toUpperCase(Locale.ROOT);
        String item = INVENTORY.get(key);
        if (item == null) {
            return "SKU %s was not found. Known SKUs: %s".formatted(key, String.join(", ", INVENTORY.keySet()));
        }
        return key + ": " + item;
    }

    @McpTool(name = "currency_exchange",
            description = "Convert an amount between currencies using demo exchange rates.")
    public String currencyExchange(
            @McpToolParam(description = "Source currency code such as EUR", required = true) String from,
            @McpToolParam(description = "Target currency code such as INR", required = true) String to,
            @McpToolParam(description = "Amount to convert. Defaults to 1.", required = false) Double amount) {

        String source = from == null ? "" : from.trim().toUpperCase(Locale.ROOT);
        String target = to == null ? "" : to.trim().toUpperCase(Locale.ROOT);
        double value = amount == null ? 1.0 : amount;
        double rate = source.equals(target) ? 1.0 : FX_RATES.getOrDefault(source + "/" + target, -1.0);

        if (rate < 0) {
            return "No demo rate for %s -> %s. Supported pairs: %s"
                    .formatted(source, target, String.join(", ", FX_RATES.keySet()));
        }
        return "1 %s = %.4f %s (demo rate)\n%.2f %s = %.2f %s"
                .formatted(source, rate, target, value, source, value * rate, target);
    }

    @McpTool(name = "shipping_quote",
            description = "Calculate a demo shipping quote for a destination country, weight and service level.")
    public String shippingQuote(
            @McpToolParam(description = "Destination country, for example Germany", required = true) String country,
            @McpToolParam(description = "Package weight in kilograms. Defaults to 1.", required = false) Double weightKg,
            @McpToolParam(description = "Use expedited shipping. Defaults to false.", required = false) Boolean expedited) {

        String destination = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        double weight = weightKg == null ? 1.0 : Math.max(0.1, weightKg);
        boolean fast = Boolean.TRUE.equals(expedited);
        boolean eu = EU_COUNTRIES.contains(destination);
        double base = eu ? 9.5 : 18.0;
        double total = (base + weight * 2.5) * (fast ? 1.8 : 1.0);

        return """
                Shipping quote for %s:
                - weight: %.2f kg
                - service: %s
                - zone: %s
                - estimated delivery: %s
                - total: %.2f EUR
                """.formatted(destination, weight, fast ? "expedited" : "standard",
                eu ? "EU" : "international", fast ? "1-2 business days" : "4-7 business days", total).trim();
    }

    @McpResource(uri = "memo://agent-demo/returns-policy",
            name = "returns-policy",
            description = "Demo return and refund policy for the agent demo store.")
    public String returnsPolicy() {
        return """
                Returns are accepted within 30 days of delivery when the product is unused
                and in its original packaging. Refunds are issued to the original payment
                method within 5 business days after inspection. Customers pay return
                shipping unless the item arrived damaged or the wrong item was shipped.
                """.trim();
    }

    private static Map<String, String> inventory() {
        Map<String, String> items = new LinkedHashMap<>();
        items.put("SKU-1001",
                "Mechanical keyboard (US layout) | available=42 | warehouse=Berlin | restock=2026-11-25 | unitPriceEUR=89.00");
        items.put("SKU-1002",
                "Noise-cancelling headphones | available=7 | warehouse=Amsterdam | restock=2026-12-02 | unitPriceEUR=149.50");
        items.put("SKU-1003",
                "USB-C dock | available=0 | warehouse=Berlin | restock=2026-11-20 | unitPriceEUR=119.00");
        items.put("SKU-1004",
                "4K webcam | available=115 | warehouse=Austin | restock=n/a | unitPriceUSD=79.00");
        return Map.copyOf(items);
    }

    private static Map<String, Double> fxRates() {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("EUR/USD", 1.09);
        rates.put("USD/EUR", 0.92);
        rates.put("EUR/INR", 96.40);
        rates.put("INR/EUR", 0.0104);
        rates.put("USD/INR", 88.60);
        rates.put("INR/USD", 0.0113);
        rates.put("GBP/USD", 1.27);
        rates.put("USD/GBP", 0.79);
        rates.put("GBP/EUR", 1.16);
        rates.put("EUR/GBP", 0.86);
        return Map.copyOf(rates);
    }
}
