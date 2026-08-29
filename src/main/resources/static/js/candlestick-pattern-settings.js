(function () {
    'use strict';
    document.addEventListener('DOMContentLoaded', function () {
        const filter = document.getElementById('candlestickPatternFilter');
        if (!filter) return;
        const cards = Array.from(document.querySelectorAll('.candlestick-pattern-card'));
        const empty = document.getElementById('candlestickPatternEmpty');
        document.querySelectorAll('.candlestick-circuit-breaker-card').forEach(function (card) {
            const enabled = card.querySelector('.candlestick-circuit-breaker-enabled');
            if (!enabled) return;
            const refreshCircuitBreaker = function () {
                card.classList.toggle('disabled', !enabled.checked);
            };
            enabled.addEventListener('change', refreshCircuitBreaker);
            refreshCircuitBreaker();
        });
        cards.forEach(function (card) {
            const mode = card.querySelector('select[name$=".stopLossMode"]');
            const value = card.querySelector('input[name$=".stopLossValuePercent"]');
            const valueLabel = value && value.closest('.candlestick-value-control')
                ? value.closest('.candlestick-value-control').querySelector('span:first-child') : null;
            if (!mode || !value) return;
            const refreshStopLossControl = function () {
                const fixed = mode.value === 'FIXED_ENTRY_PERCENT';
                value.min = fixed ? '0.1' : '0';
                if (fixed && Number(value.value) < 0.1) value.value = '0.1';
                if (valueLabel) valueLabel.textContent = fixed
                    ? 'Distance from entry (%)' : 'Additional buffer (%)';
            };
            mode.addEventListener('change', refreshStopLossControl);
            refreshStopLossControl();
        });
        filter.addEventListener('input', function () {
            const query = filter.value.trim().toLocaleLowerCase();
            let visible = 0;
            cards.forEach(function (card) {
                const matches = !query || (card.dataset.patternSearch || '').toLocaleLowerCase().includes(query);
                card.hidden = !matches;
                if (matches) visible += 1;
            });
            if (empty) empty.hidden = visible !== 0;
        });
    });
})();
