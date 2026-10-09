(() => {
    "use strict";
    const i18n = window.StockWatchI18n;

    function initializeDashboardViews() {
        const viewSections = Array.from(document.querySelectorAll("[data-dashboard-view]"));
        const viewLayout = document.querySelector("[data-dashboard-view-layout]");
        if (viewSections.length === 0) return;

        // Ticker alerts is its own page now; old /home#ticker-alerts links and bookmarks go there.
        function redirectLegacyTickerAlertsLink() {
            if (window.location.hash !== "#ticker-alerts" || viewLayout?.dataset.activeView === "alerts") return false;
            window.location.replace("/ticker-alerts");
            return true;
        }

        function activateView() {
            if (redirectLegacyTickerAlertsLink()) return;
            const view = viewLayout?.dataset.activeView || "technical";
            viewSections.forEach(section => {
                section.hidden = section.dataset.dashboardView !== view;
            });
            if (viewLayout) {
                viewLayout.classList.toggle("ticker-alerts-active", view === "alerts");
            }
            window.dispatchEvent(new Event("stockwatch:dashboard-view"));
        }

        activateView();
        window.addEventListener("hashchange", activateView);
        window.addEventListener("pageshow", activateView);
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
                announcement.textContent = i18n.plural(group === "stocks" ? "dashboard.showing.stocks" : "dashboard.showing.funds", visibleCount, visibleCount);
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
                        throw new Error(i18n.t("dashboard.markReadFailed"));
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
                        status.textContent = i18n.t("dashboard.markedRead");
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
                        status.textContent = error.message || i18n.t("dashboard.markReadFailed");
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
                        throw new Error(i18n.t("dashboard.markAllFailed"));
                    }
                    reloadTickerAlerts();
                } catch (error) {
                    button.disabled = false;
                    button.removeAttribute("aria-busy");
                    if (status) {
                        status.textContent = error.message || i18n.t("dashboard.markAllFailed");
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

        function selectedOutlookSubscriptionIds() {
            return Array.from(ruleList.querySelectorAll("input[data-outlook-subscription-id]:checked"))
                .map(input => Number(input.dataset.outlookSubscriptionId))
                .filter(Number.isSafeInteger);
        }

        function updateSelectionAction() {
            const selectedCount = selectedRuleIds().length + selectedOutlookSubscriptionIds().length;
            deleteSelectedButton.disabled = selectedCount === 0;
            deleteSelectedButton.textContent = selectedCount === 0
                ? i18n.t("dashboard.deleteSelected")
                : i18n.t("dashboard.deleteSelectedCount", selectedCount);
        }

        function resetDialog() {
            pendingSymbol = null;
            activeRuleCount = 0;
            ruleList.replaceChildren();
            status.textContent = "";
            deleteSelectedButton.disabled = true;
            deleteSelectedButton.textContent = i18n.t("dashboard.deleteSelected");
            deleteAllButton.disabled = false;
            deleteAllButton.textContent = i18n.t("dashboard.deleteAll");
        }

        function appendRuleOption(rule, automatedAnalysis = false) {
            const item = document.createElement("li");
            const label = document.createElement("label");
            label.className = "company-unfollow-rule-option";
            const input = document.createElement("input");
            input.type = "checkbox";
            if (automatedAnalysis) input.dataset.outlookSubscriptionId = String(rule.id);
            else input.dataset.ruleId = String(rule.id);
            const tradeSignalLabel = i18n.has(`signalSide.${rule.tradeSignal}`) ? i18n.t(`signalSide.${rule.tradeSignal}`) : rule.tradeSignal;
            input.setAttribute("aria-label", automatedAnalysis
                ? i18n.t("dashboard.selectRule", rule.familyLabel, rule.intervalLabel)
                : i18n.t("dashboard.selectRuleSide", rule.familyLabel, rule.intervalLabel, tradeSignalLabel));
            const copy = document.createElement("span");
            copy.className = "company-unfollow-rule-copy";
            const title = document.createElement("strong");
            title.textContent = automatedAnalysis ? rule.familyLabel : `${rule.familyLabel} · ${tradeSignalLabel}`;
            const interval = document.createElement("small");
            interval.textContent = i18n.t("dashboard.intervalSuffix", rule.intervalLabel);
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
                        throw new Error(i18n.t("dashboard.rulesLoadFailed"));
                    }
                    const payload = await response.json();
                    const activeRules = Array.isArray(payload.activeRules) ? payload.activeRules : [];
                    resetDialog();
                    pendingSymbol = symbol;
                    companyName.textContent = button.dataset.companyName || symbol;
                    const outlooks = Array.isArray(payload.outlookSubscriptions) ? payload.outlookSubscriptions : [];
                    activeRuleCount = activeRules.length + outlooks.length;
                    activeRules.forEach(rule => appendRuleOption(rule));
                    outlooks.forEach(subscription => appendRuleOption(subscription, true));
                    if (activeRuleCount === 0) {
                        const item = document.createElement("li");
                        item.className = "company-unfollow-empty-rule";
                        item.textContent = i18n.t("dashboard.noRules");
                        ruleList.append(item);
                        deleteAllButton.disabled = true;
                    }
                    dialog.showModal();
                } catch (error) {
                    resetDialog();
                    companyName.textContent = button.dataset.companyName || symbol;
                    status.textContent = error.message || i18n.t("dashboard.rulesLoadFailed");
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
            if (event.target.matches("input[data-rule-id], input[data-outlook-subscription-id]")) {
                updateSelectionAction();
            }
        });

        async function deleteRules(deleteAll) {
            const ruleIds = deleteAll ? [] : selectedRuleIds();
            const outlookSubscriptionIds = deleteAll ? [] : selectedOutlookSubscriptionIds();
            const selectedCount = ruleIds.length + outlookSubscriptionIds.length;
            if (!pendingSymbol || !csrfToken || (!deleteAll && selectedCount === 0)) {
                return;
            }
            deleteSelectedButton.disabled = true;
            deleteAllButton.disabled = true;
            if (deleteAll) {
                deleteAllButton.textContent = i18n.t("dashboard.deletingAll");
                status.textContent = i18n.t("dashboard.switchingOffAll");
            } else {
                deleteSelectedButton.textContent = i18n.t("dashboard.deletingSelected");
                status.textContent = i18n.plural("dashboard.switchingOffSelected", selectedCount, selectedCount);
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
                    request.body = JSON.stringify({ruleIds, outlookSubscriptionIds});
                }
                const response = await fetch(endpoint, request);
                if (!response.ok) {
                    const error = await response.json().catch(() => ({}));
                    throw new Error(error.error || i18n.t("dashboard.deleteFailed"));
                }
                window.location.reload();
            } catch (error) {
                status.textContent = error.message || i18n.t("dashboard.deleteFailed");
                deleteAllButton.disabled = activeRuleCount === 0;
                deleteAllButton.textContent = i18n.t("dashboard.deleteAll");
                updateSelectionAction();
            }
        }

        deleteSelectedButton.addEventListener("click", () => deleteRules(false));
        deleteAllButton.addEventListener("click", () => deleteRules(true));
    }

    function initializeTemporaryTopUsUniverse() {
        const followButton = document.getElementById("followTopUs200Button");
        const unfollowButton = document.getElementById("unfollowAllTickersButton");
        const status = document.getElementById("followTopUs200Status");
        if ((!followButton && !unfollowButton) || !status) {
            return;
        }
        const csrfToken = document.getElementById("accountThemeSync")?.dataset.csrfToken;

        followButton?.addEventListener("click", async () => {
            if (!csrfToken) {
                status.textContent = i18n.t("dashboard.tokenUnavailable");
                return;
            }
            const confirmed = window.confirm(
                i18n.t("dashboard.loadTestConfirm")
            );
            if (!confirmed) {
                return;
            }
            followButton.disabled = true;
            followButton.setAttribute("aria-busy", "true");
            followButton.textContent = i18n.t("dashboard.activatingRules");
            status.textContent = i18n.t("dashboard.creatingUniverse");
            try {
                const response = await fetch("/api/alerts/testing/top-us-200", {
                    method: "POST",
                    headers: {"X-CSRF-TOKEN": csrfToken},
                    credentials: "same-origin"
                });
                const payload = await response.json().catch(() => ({}));
                if (!response.ok) {
                    throw new Error(payload.error || i18n.t("dashboard.universeFailed"));
                }
                status.textContent = i18n.t("dashboard.universeActive", payload.companies, payload.activeRules);
                window.location.reload();
            } catch (error) {
                followButton.disabled = false;
                followButton.removeAttribute("aria-busy");
                followButton.textContent = i18n.t("dashboard.followTop200");
                status.textContent = error.message || i18n.t("dashboard.universeFailed");
            }
        });

        unfollowButton?.addEventListener("click", async () => {
            if (!csrfToken) {
                status.textContent = i18n.t("dashboard.tokenUnavailable");
                return;
            }
            const confirmed = window.confirm(
                i18n.t("dashboard.unfollowAllConfirm")
            );
            if (!confirmed) {
                return;
            }
            unfollowButton.disabled = true;
            unfollowButton.setAttribute("aria-busy", "true");
            unfollowButton.textContent = i18n.t("dashboard.unfollowingAll");
            status.textContent = i18n.t("dashboard.switchingOffFollowed");
            try {
                const response = await fetch("/api/alerts", {
                    method: "DELETE",
                    headers: {"X-CSRF-TOKEN": csrfToken},
                    credentials: "same-origin"
                });
                const payload = await response.json().catch(() => ({}));
                if (!response.ok) {
                    throw new Error(payload.error || i18n.t("dashboard.unfollowFailed"));
                }
                status.textContent = i18n.t("dashboard.rulesSwitchedOff", payload.unfollowedRules);
                window.location.reload();
            } catch (error) {
                unfollowButton.disabled = false;
                unfollowButton.removeAttribute("aria-busy");
                unfollowButton.textContent = i18n.t("dashboard.unfollowAllTickers");
                status.textContent = error.message || i18n.t("dashboard.unfollowFailed");
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
