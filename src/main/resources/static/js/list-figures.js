// Two small figures for the pages that list triggers and signals (a custom alert's page, the signal archives): how
// many arrived each day, and how the measured ones ended, one dot each around zero.
(() => {
  const i18n = window.StockWatchI18n;
  const text = window.StockWatchCustomAlerts;
  const el = text.element;
  const DAY = 86400000;
  const SVG = 'http://www.w3.org/2000/svg';
  const startOf = value => { const day = new Date(value); day.setHours(0, 0, 0, 0); return day.getTime(); };

  /**
   * Items per day from the first one (or {@code since}, at most 180 days back) to today, on a baseline with both ends
   * named. A square-root scale keeps a busy day from flattening the rest. Marked items (stocks already matching when
   * an alert was saved) are no item of their own, so they are a marker on their day, not a bar.
   *
   * @param items [{ time, count = 1, marked }]
   * @param keys  message keys: caption, aria (plural: items, days), day (plural), marked (plural)
   */
  function history(box, items, { since = null, keys = {}, slim = false } = {}) {
    if (!box) return;
    const key = { caption: 'ca.alert.historyCaption', aria: 'ca.alert.historyAria', day: 'ca.alert.historyDay', marked: 'ca.alert.historyAtSave', ...keys };
    const today = startOf(Date.now());
    const firsts = [since ?? Date.now(), ...items.map(item => item.time)];
    const days = Math.max(14, Math.min(180, Math.round((today - startOf(Math.min(...firsts))) / DAY) + 1));
    const counted = new Array(days).fill(0);
    const marked = new Array(days).fill(0);
    items.forEach(item => {
      const at = days - 1 - Math.round((today - startOf(item.time)) / DAY);
      if (at < 0 || at >= days) return;
      (item.marked ? marked : counted)[at] += item.count ?? 1;
    });
    const width = days * 4;
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', `0 0 ${width} 28`);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('class', 'ca-history-bars');
    svg.setAttribute('role', 'img');
    const total = counted.reduce((sum, value) => sum + value, 0);
    svg.setAttribute('aria-label', i18n.plural(key.aria, total, total, days));
    const rect = (attributes, className) => {
      const node = document.createElementNS(SVG, 'rect');
      Object.entries(attributes).forEach(([name, value]) => node.setAttribute(name, value));
      if (className) node.setAttribute('class', className);
      return node;
    };
    const titled = (node, words) => {
      const title = document.createElementNS(SVG, 'title');
      title.textContent = words;
      node.append(title);
      return node;
    };
    svg.append(rect({ x: 0, y: 27, width, height: 1 }, 'ca-history-base'));
    const top = Math.sqrt(Math.max(1, ...counted));
    counted.forEach((value, at) => {
      if (!value) return;
      const height = Math.max(4, Math.round((Math.sqrt(value) / top) * 24));
      svg.append(titled(rect({ x: at * 4 + (slim ? 1.2 : 0.5), y: 27 - height, width: slim ? 1.6 : 3, height }),
        `${text.date(today - (days - 1 - at) * DAY, true)}: ${i18n.plural(key.day, value, value)}`));
    });
    marked.forEach((value, at) => {
      if (!value) return;
      svg.append(titled(rect({ x: at * 4, y: 21, width: 4, height: 4 }, 'ca-history-save'),
        `${text.date(today - (days - 1 - at) * DAY, true)}: ${i18n.plural(key.marked, value, value)}`));
    });
    const caption = el('p', 'ca-history-caption');
    caption.append(el('span', null, i18n.t(key.caption)));
    const markedTotal = marked.reduce((sum, value) => sum + value, 0);
    if (markedTotal) {
      const legend = el('span', 'ca-history-save-key');
      legend.append(el('i'), i18n.plural(key.marked, markedTotal, markedTotal));
      caption.append(legend);
    }
    const axis = el('div', 'ca-history-axis-labels');
    axis.append(el('span', null, text.date(today - (days - 1) * DAY, true)), el('span', null, i18n.t('ca.alert.historyToday')));
    box.replaceChildren(caption, svg, axis);
  }

  /**
   * Every measured item's result as a dot on one line from the lowest to the highest, zero always on it, the median
   * marked, and how many ended higher and lower. Each dot opens its item. With a dozen items the spread says as much
   * as the median.
   *
   * @param items [{ value, symbol, time, href }]
   */
  function spread(box, items) {
    if (!box) return;
    const measured = items.filter(item => Number.isFinite(item.value)).sort((a, b) => a.value - b.value);
    const values = measured.map(item => item.value);
    if (!values.length) { box.remove(); return; }
    // The line runs from the lowest result to the highest, zero always on it; its ends are named underneath.
    const percent = new Intl.NumberFormat(i18n.tag || 'en', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    const signed = value => `${value > 0 ? '+' : value < 0 ? '−' : ''}${percent.format(Math.abs(value))}%`;
    const low = Math.min(0, values[0]);
    const high = Math.max(0, values.at(-1));
    const higher = values.filter(value => value > 0).length;
    const flat = values.filter(value => value === 0).length;
    const lower = values.length - higher - flat;
    const middle = values.length % 2 ? values[values.length >> 1] : (values[values.length / 2 - 1] + values[values.length / 2]) / 2;
    const labels = el('div', 'ca-spread-labels');
    labels.append(el('span', null, signed(low)), el('span', 'ca-spread-count', flat ? i18n.t('ca.alert.spreadCountFlat', higher, lower, flat) : i18n.t('ca.alert.spreadCount', higher, lower)),
      el('span', null, signed(high)));
    const draw = () => {
      // Drawn to the tile's width, so the dots stay round.
      const width = Math.max(120, Math.round(box.clientWidth));
      const x = value => 6 + ((value - low) / ((high - low) || 1)) * (width - 12);
      const svg = document.createElementNS(SVG, 'svg');
      svg.setAttribute('viewBox', `0 -12 ${width} 46`);
      svg.setAttribute('class', 'ca-spread');
      svg.setAttribute('role', 'img');
      svg.setAttribute('aria-label', flat ? i18n.t('ca.alert.spreadAriaFlat', higher, lower, flat, signed(values[0]), signed(values.at(-1)))
        : i18n.t('ca.alert.spreadAria', higher, lower, signed(values[0]), signed(values.at(-1))));
      const line = (x1, y1, x2, y2, className) => {
        const node = document.createElementNS(SVG, 'line');
        Object.entries({ x1, y1, x2, y2, class: className }).forEach(([name, value]) => node.setAttribute(name, value));
        return node;
      };
      svg.append(line(6, 17, width - 6, 17, 'ca-spread-axis'), line(x(0), 3, x(0), 31, 'ca-spread-zero'));
      svg.append(line(x(middle), 5, x(middle), 29, 'ca-spread-median'));
      // Dots that would touch step above and below the line, so every item stays visible; unchanged ones sit side by
      // side on the zero line, hollow, so they don't read as the median's mark.
      const placed = [];
      let unchanged = 0;
      measured.forEach(item => {
        let cx = x(item.value);
        if (item.value === 0) {
          cx = x(0) + (unchanged % 2 ? -1 : 1) * Math.ceil(unchanged / 2) * 9;
          unchanged++;
        }
        const cy = 17 + ([0, -8, 8, -15, 15].find(offset => placed.every(dot => Math.hypot(dot.cx - cx, dot.cy - 17 - offset) >= 8.5)) ?? 0);
        placed.push({ cx, cy });
        const label = `${item.symbol} ${signed(item.value)} · ${text.date(item.time, true)}`;
        const dot = document.createElementNS(SVG, 'circle');
        Object.entries({ cx, cy, r: 4, class: item.value > 0 ? 'is-up' : item.value < 0 ? 'is-down' : 'is-flat' }).forEach(([name, value]) => dot.setAttribute(name, value));
        const title = document.createElementNS(SVG, 'title');
        title.textContent = label;
        if (!item.href) { dot.append(title); svg.append(dot); return; }
        const link = document.createElementNS(SVG, 'a');
        link.setAttribute('href', item.href);
        link.setAttribute('aria-label', label);
        link.append(title, dot);
        svg.append(link);
      });
      const zero = document.createElementNS(SVG, 'text');
      Object.entries({ x: x(0), y: -11, class: 'ca-spread-zero-label', 'text-anchor': x(0) < 20 ? 'start' : x(0) > width - 20 ? 'end' : 'middle', 'dominant-baseline': 'hanging' })
        .forEach(([name, value]) => zero.setAttribute(name, value));
      zero.textContent = '0%';
      svg.prepend(zero);
      box.replaceChildren(svg, labels);
    };
    draw();
    if (window.ResizeObserver) {
      let drawnAt = box.clientWidth;
      new ResizeObserver(() => {
        if (Math.abs(box.clientWidth - drawnAt) < 4) return;
        drawnAt = box.clientWidth;
        draw();
      }).observe(box);
    }
  }

  window.StockWatchListFigures = Object.freeze({ history, spread });
})();
