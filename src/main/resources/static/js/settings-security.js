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
            button.textContent = button.dataset.pendingLabel || 'Please wait...';
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
            ? `Code expires in ${Math.floor(seconds / 60)}m ${seconds % 60}s. Check your spam folder if it has not arrived.`
            : 'This code has expired. Request a new code above to continue.';
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
