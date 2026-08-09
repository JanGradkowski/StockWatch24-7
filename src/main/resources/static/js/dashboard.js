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

    function initializeDashboard() {
        initializeDashboardViews();
        initializeWatchFilters();
        initializeNotificationReadButtons();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeDashboard, {once: true});
    } else {
        initializeDashboard();
    }
})();
