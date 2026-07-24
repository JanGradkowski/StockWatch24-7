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
            button.addEventListener("click", () => activateView(button.dataset.dashboardViewButton));
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

        activateView("technical");
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

    function initializeDashboard() {
        initializeDashboardViews();
        initializeWatchFilters();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeDashboard, {once: true});
    } else {
        initializeDashboard();
    }
})();
