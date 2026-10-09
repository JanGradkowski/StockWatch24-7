(function () {
  'use strict';

  function updateProfile(card) {
    var total = 0;
    card.querySelectorAll('.scoring-component-row').forEach(function (row) {
      var included = row.querySelector('.scoring-include');
      var points = row.querySelector('.scoring-points');
      row.classList.toggle('excluded', !included.checked);
      if (included.checked) total += Number.parseInt(points.value, 10) || 0;
    });
    card.querySelectorAll('.scoring-confluence-row').forEach(function (row) {
      var included = row.querySelector('.scoring-confluence-include');
      row.classList.toggle('excluded', !included.checked);
    });
    var valid = total === 100;
    card.classList.toggle('invalid-total', !valid);
    card.querySelector('.scoring-total').textContent = total + ' / 100';
    var i18n = window.StockWatchI18n;
    card.querySelector('.scoring-total-message').textContent = valid
      ? i18n.t('scoring.ready')
      : (total < 100 ? i18n.plural('scoring.pointsNeeded', 100 - total, 100 - total)
        : i18n.plural('scoring.pointsOver', total - 100, total - 100));
    return valid;
  }

  document.addEventListener('DOMContentLoaded', function () {
    var form = document.getElementById('signalScoringForm');
    if (!form) return;
    var cards = Array.from(form.querySelectorAll('[data-scoring-profile]'));
    var apply = document.getElementById('applyScoringChanges');

    function updateAll() {
      var valid = cards.map(updateProfile).every(Boolean);
      apply.disabled = !valid;
      apply.setAttribute('aria-disabled', String(!valid));
    }

    form.addEventListener('input', updateAll);
    form.addEventListener('change', updateAll);
    form.addEventListener('submit', function (event) {
      updateAll();
      if (apply.disabled) {
        event.preventDefault();
        var invalid = form.querySelector('.invalid-total');
        if (invalid) invalid.scrollIntoView({behavior: 'smooth', block: 'center'});
      }
    });
    updateAll();
  });
})();
