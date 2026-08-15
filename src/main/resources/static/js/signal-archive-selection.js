(() => {
    const forms = document.querySelectorAll("[data-signal-delete-form]");
    const dialog = document.querySelector("[data-delete-dialog]");
    if (forms.length === 0 || !dialog) {
        return;
    }

    const dialogTitle = dialog.querySelector("[data-delete-dialog-title]");
    const dialogMessage = dialog.querySelector("[data-delete-dialog-message]");
    const cancelButton = dialog.querySelector("[data-delete-cancel]");
    const confirmButton = dialog.querySelector("[data-delete-confirm]");
    let pendingSubmission = null;

    const closeDialog = () => {
        pendingSubmission = null;
        if (dialog.open) {
            dialog.close();
        }
    };

    forms.forEach((form) => {
        const rowCheckboxes = [...form.querySelectorAll("[data-signal-checkbox]")];
        const selectAll = form.querySelector("[data-select-all]");
        const selectedCount = form.querySelector("[data-selected-count]");
        const bulkDelete = form.querySelector("[data-bulk-delete]");

        const refreshSelection = () => {
            const count = rowCheckboxes.filter((checkbox) => checkbox.checked).length;
            selectedCount.textContent = String(count);
            bulkDelete.disabled = count === 0;
            selectAll.checked = count > 0 && count === rowCheckboxes.length;
            selectAll.indeterminate = count > 0 && count < rowCheckboxes.length;
        };

        selectAll.addEventListener("change", () => {
            rowCheckboxes.forEach((checkbox) => {
                checkbox.checked = selectAll.checked;
            });
            refreshSelection();
        });
        rowCheckboxes.forEach((checkbox) => checkbox.addEventListener("change", refreshSelection));

        form.addEventListener("submit", (event) => {
            if (form.dataset.deleteConfirmed === "true") {
                delete form.dataset.deleteConfirmed;
                return;
            }

            const submitter = event.submitter;
            const singleDelete = submitter?.matches("[data-single-delete]") === true;
            const count = singleDelete
                ? 1
                : rowCheckboxes.filter((checkbox) => checkbox.checked).length;
            if (count === 0) {
                event.preventDefault();
                refreshSelection();
                return;
            }

            event.preventDefault();
            const archiveKind = form.dataset.archiveKind || "signal";
            const singleLabel = submitter?.dataset.signalLabel;
            dialogTitle.textContent = singleDelete
                ? `Delete ${archiveKind}?`
                : `Delete ${count} ${archiveKind}${count === 1 ? "" : "s"}?`;
            dialogMessage.textContent = singleDelete && singleLabel
                ? `“${singleLabel}” will be permanently removed from your account archive. This cannot be undone.`
                : `The selected ${archiveKind}${count === 1 ? "" : "s"} will be permanently removed from your account archive. This cannot be undone.`;
            pendingSubmission = {form, submitter};
            dialog.showModal();
            confirmButton.focus();
        });

        refreshSelection();
    });

    cancelButton.addEventListener("click", closeDialog);
    dialog.addEventListener("cancel", () => {
        pendingSubmission = null;
    });
    dialog.addEventListener("click", (event) => {
        if (event.target === dialog) {
            closeDialog();
        }
    });
    confirmButton.addEventListener("click", () => {
        if (!pendingSubmission) {
            closeDialog();
            return;
        }
        const {form, submitter} = pendingSubmission;
        pendingSubmission = null;
        form.dataset.deleteConfirmed = "true";
        dialog.close();
        form.requestSubmit(submitter || undefined);
    });
})();
