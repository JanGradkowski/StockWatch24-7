(() => {
    "use strict";

    function initializeDashboardViews() {
        const viewButtons = Array.from(document.querySelectorAll("[data-dashboard-view-button]"));
        const viewSections = Array.from(document.querySelectorAll("[data-dashboard-view]"));
        const viewLayout = document.querySelector("[data-dashboard-view-layout]");
        if (viewButtons.length === 0 || viewSections.length === 0) {
            return;
        }

        function activateView(view, moveFocus = false) {
            const activeButton = viewButtons.find(button => button.dataset.dashboardViewButton === view);
            if (!activeButton) {
                return;
            }

            viewButtons.forEach(button => {
                const selected = button === activeButton;
                button.classList.toggle("active", selected);
                button.setAttribute("aria-pressed", String(selected));
            });

            viewSections.forEach(section => {
                section.hidden = section.dataset.dashboardView !== view;
            });

            if (viewLayout) {
                viewLayout.classList.toggle("ticker-alerts-active", view === "alerts");
            }

            if (moveFocus) {
                activeButton.focus();
            }
        }

        viewButtons.forEach((button, index) => {
            button.addEventListener("click", () => {
                const view = button.dataset.dashboardViewButton;
                activateView(view);
                const url = new URL(window.location.href);
                url.hash = view === "alerts" ? "ticker-alerts" : "";
                window.history.replaceState(null, "", url);
            });
            button.addEventListener("keydown", event => {
                let targetIndex = null;
                if (event.key === "ArrowRight" || event.key === "ArrowDown") {
                    targetIndex = (index + 1) % viewButtons.length;
                } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
                    targetIndex = (index - 1 + viewButtons.length) % viewButtons.length;
                } else if (event.key === "Home") {
                    targetIndex = 0;
                } else if (event.key === "End") {
                    targetIndex = viewButtons.length - 1;
                }

                if (targetIndex != null) {
                    event.preventDefault();
                    activateView(viewButtons[targetIndex].dataset.dashboardViewButton, true);
                }
            });
        });

        activateView(window.location.hash === "#ticker-alerts" ? "alerts" : "technical");
    }

    function initializeWatchFilters() {
        const filterButtons = Array.from(document.querySelectorAll("[data-watch-filter]"));
        if (filterButtons.length === 0) {
            return;
        }

        const instrumentRows = Array.from(document.querySelectorAll("[data-instrument-group]"));
        const emptyStates = Array.from(document.querySelectorAll("[data-watch-empty]"));
        const listHeading = document.querySelector("[data-watch-list-head]");
        const announcement = document.getElementById("watchFilterAnnouncement");

        function activateFilter(group, moveFocus = false) {
            const activeButton = filterButtons.find(button => button.dataset.watchFilter === group);
            if (!activeButton) {
                return;
            }

            filterButtons.forEach(button => {
                const selected = button === activeButton;
                button.classList.toggle("active", selected);
                button.setAttribute("aria-pressed", String(selected));
            });

            let visibleCount = 0;
            instrumentRows.forEach(row => {
                const visible = row.dataset.instrumentGroup === group;
                row.hidden = !visible;
                if (visible) {
                    visibleCount += 1;
                }
            });

            emptyStates.forEach(emptyState => {
                emptyState.hidden = emptyState.dataset.watchEmpty !== group || visibleCount > 0;
            });

            if (listHeading) {
                listHeading.hidden = visibleCount === 0;
            }

            if (announcement) {
                const singular = group === "stocks" ? "followed stock" : "followed index or ETF";
                const plural = group === "stocks" ? "followed stocks" : "followed indexes or ETFs";
                announcement.textContent = `Showing ${visibleCount} ${visibleCount === 1 ? singular : plural}`;
            }

            if (moveFocus) {
                activeButton.focus();
            }
        }

        filterButtons.forEach((button, index) => {
            button.addEventListener("click", () => activateFilter(button.dataset.watchFilter));
            button.addEventListener("keydown", event => {
                let targetIndex = null;
                if (event.key === "ArrowRight" || event.key === "ArrowDown") {
                    targetIndex = (index + 1) % filterButtons.length;
                } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
                    targetIndex = (index - 1 + filterButtons.length) % filterButtons.length;
                } else if (event.key === "Home") {
                    targetIndex = 0;
                } else if (event.key === "End") {
                    targetIndex = filterButtons.length - 1;
                }

                if (targetIndex != null) {
                    event.preventDefault();
                    activateFilter(filterButtons[targetIndex].dataset.watchFilter, true);
                }
            });
        });

        activateFilter("stocks");
    }

    function initializeNotificationReadButtons() {
        const buttons = Array.from(document.querySelectorAll("[data-notification-read]"));
        const readAllButtons = Array.from(document.querySelectorAll("[data-notification-read-all]"));
        if (buttons.length === 0 && readAllButtons.length === 0) {
            return;
        }

        const csrfToken = document.getElementById("accountThemeSync")?.dataset.csrfToken;
        const status = document.getElementById("tickerNotificationReadStatus");

        function refreshPanel(panel, removedCount = 1) {
            const rows = Array.from(panel.querySelectorAll("[data-notification-row]"));
            const count = panel.querySelector("[data-notification-count]");
            const list = panel.querySelector("[data-notification-list]");
            const empty = panel.querySelector("[data-notification-empty]");
            const currentUnread = Number.parseInt(
                panel.dataset.unreadCount || count?.textContent || String(rows.length),
                10
            );
            const unread = Math.max(0, (Number.isFinite(currentUnread) ? currentUnread : rows.length) - removedCount);
            panel.dataset.unreadCount = String(unread);
            if (count) {
                count.textContent = String(unread);
            }
            if (list) {
                list.hidden = rows.length === 0;
            }
            if (empty) {
                empty.hidden = unread !== 0;
            }
            panel.querySelectorAll("[data-notification-read-all]").forEach(clearButton => {
                clearButton.hidden = unread === 0;
            });
            return unread > 0 && rows.length === 0;
        }

        function reloadTickerAlerts() {
            const url = new URL(window.location.href);
            url.hash = "ticker-alerts";
            window.history.replaceState(null, "", url);
            window.location.reload();
        }

        buttons.forEach(button => {
            button.addEventListener("click", async () => {
                const endpoint = button.dataset.readEndpoint;
                const notificationKey = button.dataset.notificationKey;
                if (!endpoint || !notificationKey || !csrfToken) {
                    return;
                }

                const matchingButtons = Array.from(document.querySelectorAll("[data-notification-read]"))
                    .filter(candidate => candidate.dataset.notificationKey === notificationKey);
                matchingButtons.forEach(candidate => {
                    candidate.disabled = true;
                    candidate.setAttribute("aria-busy", "true");
                });

                try {
                    const response = await fetch(endpoint, {
                        method: "POST",
                        headers: {"X-CSRF-TOKEN": csrfToken},
                        credentials: "same-origin"
                    });
                    if (!response.ok) {
                        throw new Error("Notification could not be marked as read.");
                    }

                    const affectedPanels = new Set();
                    Array.from(document.querySelectorAll("[data-notification-row]"))
                        .filter(row => row.dataset.notificationKey === notificationKey)
                        .forEach(row => {
                            const panel = row.closest("[data-notification-panel]");
                            if (panel) {
                                affectedPanels.add(panel);
                            }
                            row.remove();
                        });
                    const needsBackfill = Array.from(affectedPanels)
                        .map(panel => refreshPanel(panel))
                        .some(Boolean);
                    if (status) {
                        status.textContent = "Notification marked as read. It remains available in All activity signals.";
                    }
                    if (needsBackfill) {
                        reloadTickerAlerts();
                    }
                } catch (error) {
                    matchingButtons.forEach(candidate => {
                        candidate.disabled = false;
                        candidate.removeAttribute("aria-busy");
                    });
                    if (status) {
                        status.textContent = error.message || "Notification could not be marked as read.";
                    }
                }
            });
        });

        readAllButtons.forEach(button => {
            button.addEventListener("click", async () => {
                const endpoints = String(button.dataset.readEndpoints || "")
                    .split("|")
                    .filter(Boolean);
                if (endpoints.length === 0 || !csrfToken) {
                    return;
                }
                button.disabled = true;
                button.setAttribute("aria-busy", "true");
                try {
                    const responses = await Promise.all(endpoints.map(endpoint => fetch(endpoint, {
                        method: "POST",
                        headers: {"X-CSRF-TOKEN": csrfToken},
                        credentials: "same-origin"
                    })));
                    if (responses.some(response => !response.ok)) {
                        throw new Error("Notifications could not all be marked as read.");
                    }
                    reloadTickerAlerts();
                } catch (error) {
                    button.disabled = false;
                    button.removeAttribute("aria-busy");
                    if (status) {
                        status.textContent = error.message || "Notifications could not all be marked as read.";
                    }
                }
            });
        });
    }

    function initializeCompanyUnfollow() {
        const buttons = Array.from(document.querySelectorAll("[data-unfollow-company]"));
        const dialog = document.getElementById("unfollowCompanyDialog");
        if (buttons.length === 0 || !dialog) {
            return;
        }

        const csrfToken = document.getElementById("accountThemeSync")?.dataset.csrfToken;
        const companyName = document.getElementById("unfollowCompanyName");
        const ruleList = document.getElementById("unfollowCompanyRuleList");
        const status = document.getElementById("unfollowCompanyStatus");
        const deleteSelectedButton = document.getElementById("deleteSelectedCompanyRules");
        const deleteAllButton = document.getElementById("deleteAllCompanyRules");
        let pendingSymbol = null;
        let activeRuleCount = 0;

        function selectedRuleIds() {
            return Array.from(ruleList.querySelectorAll("input[data-rule-id]:checked"))
                .map(input => Number(input.dataset.ruleId))
                .filter(Number.isSafeInteger);
        }

        function updateSelectionAction() {
            const selectedCount = selectedRuleIds().length;
            deleteSelectedButton.disabled = selectedCount === 0;
            deleteSelectedButton.textContent = selectedCount === 0
                ? "Delete selected"
                : `Delete selected (${selectedCount})`;
        }

        function resetDialog() {
            pendingSymbol = null;
            activeRuleCount = 0;
            ruleList.replaceChildren();
            status.textContent = "";
            deleteSelectedButton.disabled = true;
            deleteSelectedButton.textContent = "Delete selected";
            deleteAllButton.disabled = false;
            deleteAllButton.textContent = "Delete all";
        }

        function appendRuleOption(rule) {
            const item = document.createElement("li");
            const label = document.createElement("label");
            label.className = "company-unfollow-rule-option";
            const input = document.createElement("input");
            input.type = "checkbox";
            input.dataset.ruleId = String(rule.id);
            const tradeSignalLabel = rule.tradeSignal === "SELL" ? "SELL/SHORT" : rule.tradeSignal;
            input.setAttribute("aria-label", `Select ${rule.familyLabel} ${rule.intervalLabel} ${tradeSignalLabel}`);
            const copy = document.createElement("span");
            copy.className = "company-unfollow-rule-copy";
            const title = document.createElement("strong");
            title.textContent = `${rule.familyLabel} · ${tradeSignalLabel}`;
            const interval = document.createElement("small");
            interval.textContent = `${rule.intervalLabel} interval`;
            copy.append(title, interval);
            label.append(input, copy);
            item.append(label);
            ruleList.append(item);
        }

        buttons.forEach(button => {
            button.addEventListener("click", async () => {
                button.disabled = true;
                button.setAttribute("aria-busy", "true");
                const symbol = button.dataset.symbol;
                try {
                    const response = await fetch(`/api/alerts/${encodeURIComponent(symbol)}`, {
                        credentials: "same-origin"
                    });
                    if (!response.ok) {
                        throw new Error("The followed rules could not be loaded.");
                    }
                    const payload = await response.json();
                    const activeRules = Array.isArray(payload.activeRules) ? payload.activeRules : [];
                    resetDialog();
                    pendingSymbol = symbol;
                    companyName.textContent = button.dataset.companyName || symbol;
                    activeRuleCount = activeRules.length;
                    activeRules.forEach(appendRuleOption);
                    if (activeRules.length === 0) {
                        const item = document.createElement("li");
                        item.className = "company-unfollow-empty-rule";
                        item.textContent = "No active technical rules remain for this company.";
                        ruleList.append(item);
                        deleteAllButton.disabled = true;
                    }
                    dialog.showModal();
                } catch (error) {
                    resetDialog();
                    companyName.textContent = button.dataset.companyName || symbol;
                    status.textContent = error.message || "The followed rules could not be loaded.";
                    deleteAllButton.disabled = true;
                    dialog.showModal();
                } finally {
                    button.disabled = false;
                    button.removeAttribute("aria-busy");
                }
            });
        });

        document.getElementById("cancelUnfollowCompany").addEventListener("click", () => {
            dialog.close();
            resetDialog();
        });

        ruleList.addEventListener("change", event => {
            if (event.target.matches("input[data-rule-id]")) {
                updateSelectionAction();
            }
        });

        async function deleteRules(deleteAll) {
            const ruleIds = deleteAll ? [] : selectedRuleIds();
            if (!pendingSymbol || !csrfToken || (!deleteAll && ruleIds.length === 0)) {
                return;
            }
            deleteSelectedButton.disabled = true;
            deleteAllButton.disabled = true;
            if (deleteAll) {
                deleteAllButton.textContent = "Deleting all...";
                status.textContent = "Switching off every listed technical rule...";
            } else {
                deleteSelectedButton.textContent = "Deleting selected...";
                status.textContent = `Switching off ${ruleIds.length} selected rule${ruleIds.length === 1 ? "" : "s"}...`;
            }
            try {
                const endpoint = deleteAll
                    ? `/api/alerts/${encodeURIComponent(pendingSymbol)}`
                    : `/api/alerts/${encodeURIComponent(pendingSymbol)}/rules`;
                const request = {
                    method: "DELETE",
                    headers: {"X-CSRF-TOKEN": csrfToken},
                    credentials: "same-origin"
                };
                if (!deleteAll) {
                    request.headers["Content-Type"] = "application/json";
                    request.body = JSON.stringify({ruleIds});
                }
                const response = await fetch(endpoint, request);
                if (!response.ok) {
                    const error = await response.json().catch(() => ({}));
                    throw new Error(error.error || "The selected rules could not be deleted.");
                }
                window.location.reload();
            } catch (error) {
                status.textContent = error.message || "The selected rules could not be deleted.";
                deleteAllButton.disabled = activeRuleCount === 0;
                deleteAllButton.textContent = "Delete all";
                updateSelectionAction();
            }
        }

        deleteSelectedButton.addEventListener("click", () => deleteRules(false));
        deleteAllButton.addEventListener("click", () => deleteRules(true));
    }

    function initializeTemporaryTopUsUniverse() {
        const button = document.getElementById("followTopUs200Button");
        const status = document.getElementById("followTopUs200Status");
        if (!button || !status) {
            return;
        }
        const csrfToken = document.getElementById("accountThemeSync")?.dataset.csrfToken;
        button.addEventListener("click", async () => {
            if (!csrfToken) {
                status.textContent = "The security token is unavailable. Refresh the dashboard and try again.";
                return;
            }
            const confirmed = window.confirm(
                "Temporary load test: follow 200 U.S. companies with all 18 technical rule combinations per company (3,600 active rules). Continue?"
            );
            if (!confirmed) {
                return;
            }
            button.disabled = true;
            button.setAttribute("aria-busy", "true");
            button.textContent = "Activating 3,600 rules...";
            status.textContent = "Creating or restoring the test universe. This can take several seconds.";
            try {
                const response = await fetch("/api/alerts/testing/top-us-200", {
                    method: "POST",
                    headers: {"X-CSRF-TOKEN": csrfToken},
                    credentials: "same-origin"
                });
                const payload = await response.json().catch(() => ({}));
                if (!response.ok) {
                    throw new Error(payload.error || "The top-200 test universe could not be followed.");
                }
                status.textContent = `Active: ${payload.companies} companies and ${payload.activeRules} rules. Reloading dashboard...`;
                window.location.reload();
            } catch (error) {
                button.disabled = false;
                button.removeAttribute("aria-busy");
                button.textContent = "Follow top 200 U.S. (test)";
                status.textContent = error.message || "The top-200 test universe could not be followed.";
            }
        });
    }

    function initializeDashboard() {
        initializeDashboardViews();
        initializeWatchFilters();
        initializeNotificationReadButtons();
        initializeCompanyUnfollow();
        initializeTemporaryTopUsUniverse();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeDashboard, {once: true});
    } else {
        initializeDashboard();
    }
})();
