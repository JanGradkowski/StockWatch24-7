(() => {
    const forms = document.querySelectorAll("[data-virtual-trade-delete-form]");
    const dialog = document.querySelector("[data-virtual-trade-delete-dialog]");
    if (forms.length === 0 || !dialog) {
        return;
    }

    const message = dialog.querySelector("[data-virtual-trade-delete-message]");
    const cancelButton = dialog.querySelector("[data-virtual-trade-delete-cancel]");
    const confirmButton = dialog.querySelector("[data-virtual-trade-delete-confirm]");
    let pendingForm = null;

    const closeDialog = () => {
        pendingForm = null;
        if (dialog.open) {
            dialog.close();
        }
    };

    forms.forEach((form) => form.addEventListener("submit", (event) => {
        if (form.dataset.deleteConfirmed === "true") {
            delete form.dataset.deleteConfirmed;
            return;
        }
        event.preventDefault();
        pendingForm = form;
        const label = form.dataset.tradeLabel || "this demo trade";
        message.textContent = `“${label}” will be permanently removed from your account. This cannot be undone.`;
        dialog.showModal();
        confirmButton.focus();
    }));

    cancelButton.addEventListener("click", closeDialog);
    dialog.addEventListener("cancel", () => {
        pendingForm = null;
    });
    dialog.addEventListener("click", (event) => {
        if (event.target === dialog) {
            closeDialog();
        }
    });
    confirmButton.addEventListener("click", () => {
        if (!pendingForm) {
            closeDialog();
            return;
        }
        const form = pendingForm;
        pendingForm = null;
        form.dataset.deleteConfirmed = "true";
        dialog.close();
        form.requestSubmit();
    });
})();
