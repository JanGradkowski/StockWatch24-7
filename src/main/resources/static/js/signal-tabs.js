'use strict';
window.StockWatchSignalTabs = function (tabs, panels, unavailable, showResults) {
  function activate(name, updateHash) {
    const requested = tabs.find(tab => tab.dataset.signalTab === name);
    if (name === 'results' && requested?.dataset.resultsAvailable !== 'true') {
      unavailable();
      return false;
    }
    const selected = panels[name] ? name : 'graphical';
    tabs.forEach(tab => {
      const active = tab.dataset.signalTab === selected;
      tab.classList.toggle('active', active);
      tab.setAttribute('aria-selected', String(active));
      tab.tabIndex = active ? 0 : -1;
    });
    Object.entries(panels).forEach(([name, panel]) => {
      if (!panel) return;
      panel.hidden = name !== selected;
      panel.classList.toggle('active', name === selected);
    });
    if (updateHash) window.history.replaceState(null, '', selected === 'score' ? '#score-report' : selected === 'results' ? '#results' : '#graphical-outlook');
    if (selected === 'results') window.setTimeout(showResults, 0);
    return true;
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => activate(tab.dataset.signalTab, true));
    tab.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const position = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
        : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      const next = tabs[position];
      if (activate(next.dataset.signalTab, true)) next.focus();
    });
  });
  return activate;
};
