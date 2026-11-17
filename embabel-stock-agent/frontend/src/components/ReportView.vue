<script setup>
const props = defineProps({ report: { type: Object, default: null } })

const number = (value) => (value === null || value === undefined ? 'n/a' : Number(value).toFixed(2))
const text = (value) => (value === null || value === undefined || value === '' ? 'not available' : value)
</script>

<template>
  <div v-if="report" class="report-card">
    <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap">
      <span class="verdict" :class="report.verdict.action">{{ report.verdict.action }}</span>
      <strong style="font-size:16px">{{ report.symbol }}</strong>
      <span style="color:var(--muted);font-size:12px">
        confidence {{ Math.round(report.verdict.confidence * 100) }}% ·
        {{ report.verdict.timeHorizon }}
      </span>
    </div>

    <div class="kpis">
      <div class="kpi">
        <div class="kpi-label">Last price</div>
        <div class="kpi-value">{{ number(report.quote.currentPrice) }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">Change</div>
        <div class="kpi-value">{{ number(report.quote.changePercent) }}%</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">Support</div>
        <div class="kpi-value">{{ number(report.technicals.support) }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">Resistance</div>
        <div class="kpi-value">{{ number(report.technicals.resistance) }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">RSI (14)</div>
        <div class="kpi-value">{{ number(report.technicals.rsi14) }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">Entry / Stop / Target</div>
        <div class="kpi-value" style="font-size:12px">
          {{ report.verdict.entryZone }} / {{ report.verdict.stopLoss }} / {{ report.verdict.takeProfit }}
        </div>
      </div>
      <div class="kpi">
        <div class="kpi-label">Next earnings</div>
        <div class="kpi-value" style="font-size:13px">{{ text(report.fundamentals.nextEarningsDate) }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">Dividend / ex-date</div>
        <div class="kpi-value" style="font-size:13px">
          {{ text(report.fundamentals.dividendPerShare) }} / {{ text(report.fundamentals.exDividendDate) }}
        </div>
      </div>
    </div>

    <p style="font-size:13px; margin-bottom:6px">{{ report.verdict.rationale }}</p>
    <div style="display:flex; gap:24px; flex-wrap:wrap; font-size:12px">
      <div v-if="report.verdict.keyReasons.length">
        <strong>Why</strong>
        <ul><li v-for="(reason, i) in report.verdict.keyReasons" :key="i">{{ reason }}</li></ul>
      </div>
      <div v-if="report.verdict.risks.length">
        <strong>Risks</strong>
        <ul><li v-for="(risk, i) in report.verdict.risks" :key="i">{{ risk }}</li></ul>
      </div>
      <div>
        <strong>News sentiment: {{ report.news.sentiment }}</strong>
        <ul>
          <li v-for="(item, i) in report.news.items.slice(0, 4)" :key="i">{{ item.headline }}</li>
        </ul>
      </div>
    </div>
  </div>
</template>
