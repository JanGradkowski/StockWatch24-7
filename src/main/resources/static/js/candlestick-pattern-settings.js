(function () {
    'use strict';
    document.addEventListener('DOMContentLoaded', function () {
        const filter = document.getElementById('candlestickPatternFilter');
        if (!filter) return;
        const cards = Array.from(document.querySelectorAll('.candlestick-pattern-card'));
        const empty = document.getElementById('candlestickPatternEmpty');
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
