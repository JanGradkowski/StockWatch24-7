(() => {
    const forms = document.querySelectorAll('[data-security-submit]');
    forms.forEach(form => {
        const button = form.querySelector('button[type="submit"]');
        if (!button) return;
        const label = button.textContent;
        const reset = () => {
            button.disabled = false;
            button.textContent = label;
            form.removeAttribute('aria-busy');
        };
        form.addEventListener('submit', () => {
            button.disabled = true;
            button.textContent = button.dataset.pendingLabel || window.StockWatchI18n.t('common.pleaseWait');
            form.setAttribute('aria-busy', 'true');
        });
        window.addEventListener('pageshow', reset);
    });

    const expiry = document.querySelector('[data-code-expiry]');
    if (!expiry) return;
    const expiresAt = Date.parse(expiry.dataset.expiresAt);
    if (!Number.isFinite(expiresAt)) return;
    const update = () => {
        const seconds = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
        expiry.textContent = seconds > 0
            ? window.StockWatchI18n.t('settings.codeExpiresIn', Math.floor(seconds / 60), seconds % 60)
            : window.StockWatchI18n.t('settings.codeExpired');
        return seconds;
    };
    // Keep frequent countdown updates outside the live region; announce expiry once.
    expiry.parentElement.removeAttribute('role');
    const timer = window.setInterval(() => {
        if (update() === 0) {
            window.clearInterval(timer);
            expiry.setAttribute('role', 'status');
        }
    }, 1000);
    update();
})();
