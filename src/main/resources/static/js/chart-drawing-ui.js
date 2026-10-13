(() => {
    'use strict';

    // Toolbar, flyouts, selection toolbar, context menu, settings dialog, object tree and inline
    // text editing for the drawing engine. One instance per DrawingManager; the settings dialog
    // and context menu are shared by every chart on the page.

    const D = window.StockWatchDrawing;
    const icons = window.StockWatchDrawingIcons;
    const i18n = window.StockWatchI18n;
    const PREFERENCES_KEY = 'stockwatch:drawing-ui:v1';
    const PALETTE = ['#2962ff', '#60a5fa', '#26a69a', '#64e8bd', '#b7f34a', '#f6df69', '#ffbd59', '#ff9800', '#ef5350', '#ff647c',
        '#f472b6', '#a78bfa', '#7b61ff', '#787b86', '#e8edf3', '#101418'];
    const ALERTABLE = new Set(['horizontalLine', 'horizontalRay', 'trendLine', 'ray', 'extendedLine', 'infoLine', 'arrowLine',
        'parallelChannel', 'rectangle', 'priceLabel']);
    const SECTIONS = [
        {key: 'cursors', groups: ['cursors']},
        {key: 'lines', groups: ['lines', 'channels', 'pitchforks']},
        {key: 'fibonacci', groups: ['fibonacci', 'gann']},
        {key: 'patterns', groups: ['patterns', 'elliott', 'cycles']},
        {key: 'forecasting', groups: ['positions', 'measure', 'volume']},
        {key: 'shapes', groups: ['shapes']},
        {key: 'annotations', groups: ['annotations']}
    ];
    const MODES = ['cursor', 'eraser'];
    const INTERVALS = ['1d', '1wk', '1mo'];

    function element(tag, options = {}) {
        const node = document.createElement(tag);
        if (options.className) node.className = options.className;
        if (options.text !== undefined) node.textContent = options.text;
        Object.entries(options.attrs || {}).forEach(([name, value]) => {
            if (value !== undefined && value !== null && value !== false) node.setAttribute(name, value === true ? '' : String(value));
        });
        Object.entries(options.on || {}).forEach(([name, handler]) => node.addEventListener(name, handler));
        (options.children || []).forEach(child => child && node.append(child));
        return node;
    }

    function button(label, iconName, onClick, extra = {}) {
        const node = element('button', {
            className: extra.className || 'draw-rail-button',
            attrs: {type: 'button', title: extra.title || label, 'aria-label': label, ...extra.attrs},
            on: {click: onClick}
        });
        if (iconName) node.append(icons.icon(iconName));
        if (extra.text) node.append(element('span', {className: 'draw-button-text', text: extra.text}));
        return node;
    }

    function toolName(type) {
        return MODES.includes(type) ? i18n.t(`draw.mode.${type}`) : i18n.t(`draw.tool.${type}`);
    }

    function groupItems(group) {
        if (group === 'cursors') return MODES;
        return D.tools().filter(tool => tool.group === group).map(tool => tool.type);
    }

    function preferences() {
        const stored = D.readLocal(PREFERENCES_KEY, {}) || {};
        return {favorites: Array.isArray(stored.favorites) ? stored.favorites : [], last: stored.last || {},
            showFavorites: stored.showFavorites !== false};
    }

    function savePreferences(value) { D.writeLocal(PREFERENCES_KEY, value); }

    function shortcutLabel(shortcut) {
        return shortcut ? shortcut.split('+').map(part => (part.length === 1 ? part.toUpperCase() : part[0].toUpperCase() + part.slice(1))).join('+') : '';
    }

    // ---- templates (account when signed in, browser otherwise)

    const templates = {
        async list(type) {
            try {
                const response = await fetch(`/api/chart-state/templates?kind=DRAWING&subject=${encodeURIComponent(type)}`, {credentials: 'same-origin'});
                if (!response.ok) throw new Error('unavailable');
                const body = await response.json();
                return (body.templates || []).map(template => ({id: template.id, name: template.name, style: template.payload?.style || {}}));
            } catch (error) {
                return ((D.readLocal('stockwatch:drawing-templates:v1', {}) || {})[type] || []);
            }
        },
        async save(type, name, style) {
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            try {
                const response = await fetch(`/api/chart-state/templates?kind=DRAWING&subject=${encodeURIComponent(type)}&name=${encodeURIComponent(name)}`, {
                    method: 'PUT', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json', ...(token && header ? {[header]: token} : {})},
                    body: JSON.stringify({style})
                });
                if (!response.ok) throw new Error('unavailable');
                return true;
            } catch (error) {
                const all = D.readLocal('stockwatch:drawing-templates:v1', {}) || {};
                all[type] = [...(all[type] || []).filter(template => template.name !== name), {id: `local-${Date.now()}`, name, style}];
                return D.writeLocal('stockwatch:drawing-templates:v1', all);
            }
        },
        async remove(type, template) {
            if (String(template.id).startsWith('local-')) {
                const all = D.readLocal('stockwatch:drawing-templates:v1', {}) || {};
                all[type] = (all[type] || []).filter(item => item.id !== template.id);
                D.writeLocal('stockwatch:drawing-templates:v1', all);
                return;
            }
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            await fetch(`/api/chart-state/templates/${encodeURIComponent(template.id)}`, {
                method: 'DELETE', credentials: 'same-origin', headers: token && header ? {[header]: token} : {}
            }).catch(() => null);
        }
    };

    // ---- shared popups

    let contextMenu = null;
    let contextOwner = null;

    function closeContextMenu() {
        contextMenu?.remove();
        contextMenu = null;
        contextOwner = null;
    }

    document.addEventListener('pointerdown', event => {
        if (contextMenu && !contextMenu.contains(event.target)) closeContextMenu();
    }, true);
    document.addEventListener('keydown', event => { if (event.key === 'Escape' && contextMenu) closeContextMenu(); });

    function menu(items, clientX, clientY, owner) {
        closeContextMenu();
        const list = element('div', {className: 'draw-context-menu', attrs: {role: 'menu'}});
        items.filter(Boolean).forEach(item => {
            if (item === 'separator') {
                list.append(element('div', {className: 'draw-menu-separator', attrs: {role: 'separator'}}));
                return;
            }
            const entry = element('button', {
                className: `draw-menu-item${item.danger ? ' danger' : ''}`,
                attrs: {type: 'button', role: 'menuitem', disabled: item.disabled},
                on: {click: () => { closeContextMenu(); item.action(); }}
            });
            if (item.icon) entry.append(icons.icon(item.icon, 16));
            entry.append(element('span', {text: item.label}));
            if (item.hint) entry.append(element('span', {className: 'draw-menu-hint', text: item.hint}));
            list.append(entry);
        });
        // In full screen only the full-screen element is shown.
        (document.fullscreenElement || document.body).append(list);
        const rect = list.getBoundingClientRect();
        list.style.left = `${Math.min(clientX, window.innerWidth - rect.width - 8)}px`;
        list.style.top = `${Math.min(clientY, window.innerHeight - rect.height - 8)}px`;
        contextMenu = list;
        contextOwner = owner;
        list.querySelector('button:not([disabled])')?.focus();
        list.addEventListener('keydown', event => {
            const entries = [...list.querySelectorAll('button:not([disabled])')];
            const index = entries.indexOf(document.activeElement);
            if (event.key === 'ArrowDown') { event.preventDefault(); entries[(index + 1) % entries.length]?.focus(); }
            if (event.key === 'ArrowUp') { event.preventDefault(); entries[(index - 1 + entries.length) % entries.length]?.focus(); }
        });
        return list;
    }

    function prompt(title, value, onSubmit) {
        const dialog = element('dialog', {className: 'draw-dialog draw-prompt-dialog', attrs: {'aria-label': title}});
        const form = element('form', {className: 'draw-dialog-content', attrs: {method: 'dialog'}});
        const input = element('input', {attrs: {type: 'text', maxlength: 80, required: true, 'aria-label': title}});
        input.value = value || '';
        form.append(element('h2', {className: 'draw-dialog-title', text: title}), input, element('div', {
            className: 'draw-dialog-actions', children: [
                element('button', {className: 'btn-secondary', text: i18n.t('common.cancel'), attrs: {type: 'button'}, on: {click: () => dialog.close()}}),
                element('button', {className: 'btn-primary', text: i18n.t('draw.action.save'), attrs: {type: 'submit'}})
            ]
        }));
        form.addEventListener('submit', event => {
            event.preventDefault();
            const text = input.value.trim();
            if (!text) return;
            dialog.close();
            onSubmit(text);
        });
        dialog.addEventListener('close', () => dialog.remove());
        dialog.append(form);
        document.body.append(dialog);
        dialog.showModal();
        input.focus();
        input.select();
    }

    // ---- the UI

    class DrawingUI {
        constructor(manager, options = {}) {
            this.manager = manager;
            this.options = options;
            this.preferences = preferences();
            this.disposers = [];
            this.host = manager.container;
            this.host.classList.add('drawing-host');
            this.buildRail(options.toolbarHost);
            this.buildFavorites();
            this.buildSelectionToolbar();
            this.buildToast();
            this.disposers.push(
                manager.on('mode', () => { this.updateRail(); this.updateSelectionToolbar(); }),
                manager.on('preferences', () => this.updateRail()),
                manager.on('selection', () => this.updateSelectionToolbar()),
                manager.on('change', () => { this.updateSelectionToolbar(); this.updateRail(); this.tree?.refresh(); }),
                manager.on('drag', () => this.positionSelectionToolbar()),
                manager.on('render', () => this.positionSelectionToolbar()),
                manager.on('status', message => this.toast(message)),
                manager.on('edit', drawing => this.openProperties(drawing)),
                manager.on('edit-text', drawing => this.editText(drawing)),
                manager.on('contextmenu', ({drawing, clientX, clientY}) => this.showContextMenu(drawing, clientX, clientY))
            );
            this.updateRail();
        }

        // ---- rail

        buildRail(toolbarHost) {
            const rail = element('div', {
                className: `draw-rail${toolbarHost ? '' : ' draw-rail-overlay'}`,
                attrs: {role: 'toolbar', 'aria-label': i18n.t('draw.rail.label'), 'aria-orientation': 'vertical'}
            });
            this.sectionButtons = new Map();
            SECTIONS.forEach(section => {
                const current = this.lastTool(section);
                const main = button(toolName(current), current, () => this.activate(this.lastTool(section)), {
                    attrs: {'data-section': section.key, 'aria-pressed': 'false'}
                });
                const more = button(i18n.t('draw.rail.more', i18n.t(`draw.section.${section.key}`)), 'chevron',
                    () => this.toggleFlyout(section, wrapper), {
                        className: 'draw-rail-more', attrs: {'aria-haspopup': 'menu', 'aria-expanded': 'false'}
                    });
                const wrapper = element('div', {className: 'draw-rail-section', children: [main, more]});
                main.addEventListener('contextmenu', event => { event.preventDefault(); this.toggleFlyout(section, wrapper); });
                this.sectionButtons.set(section.key, {main, more, wrapper});
                rail.append(wrapper);
            });
            rail.append(element('span', {className: 'draw-rail-separator', attrs: {'aria-hidden': 'true'}}));
            this.utilityButtons = {
                measure: button(i18n.t('draw.mode.measure'), 'measure', () => this.manager.setMode(this.manager.mode === 'measure' ? 'cursor' : 'measure')),
                zoom: button(i18n.t('draw.mode.zoom'), 'zoom', () => this.manager.setMode(this.manager.mode === 'zoom' ? 'cursor' : 'zoom')),
                magnet: button(i18n.t('draw.rail.magnet'), 'magnet', () => {
                    const order = ['off', 'weak', 'strong'];
                    this.manager.setMagnet(order[(order.indexOf(this.manager.magnet) + 1) % order.length]);
                    this.toast(i18n.t(`draw.status.magnet.${this.manager.magnet}`));
                }),
                stay: button(i18n.t('draw.rail.stayInMode'), 'stayInMode', () => this.manager.setStayInMode(!this.manager.stayInMode)),
                lock: button(i18n.t('draw.rail.lockAll'), 'lock', () => this.manager.setLockedAll(!this.manager.lockedAll)),
                hide: button(i18n.t('draw.rail.hideAll'), 'eye', () => this.manager.setHiddenAll(!this.manager.hiddenAll)),
                remove: button(i18n.t('draw.rail.removeAll'), 'trash', event => this.confirmRemoveAll(event.currentTarget)),
                tree: button(i18n.t('draw.rail.objectTree'), 'tree', () => this.toggleTree()),
                undo: button(i18n.t('draw.rail.undo'), 'undo', () => this.manager.undo(), {title: `${i18n.t('draw.rail.undo')} (Ctrl+Z)`}),
                redo: button(i18n.t('draw.rail.redo'), 'redo', () => this.manager.redo(), {title: `${i18n.t('draw.rail.redo')} (Ctrl+Y)`}),
                favorites: button(i18n.t('draw.rail.favorites'), 'favorites', () => {
                    this.preferences.showFavorites = !this.preferences.showFavorites;
                    savePreferences(this.preferences);
                    this.renderFavorites();
                    this.updateRail();
                })
            };
            Object.values(this.utilityButtons).forEach(node => rail.append(node));
            this.rail = rail;
            if (toolbarHost) toolbarHost.append(rail); else this.host.append(rail);
        }

        lastTool(section) {
            const remembered = this.preferences.last[section.key];
            const items = section.groups.flatMap(groupItems);
            return items.includes(remembered) ? remembered : items[0];
        }

        activate(type) {
            this.closeFlyout();
            const section = SECTIONS.find(candidate => candidate.groups.flatMap(groupItems).includes(type));
            if (section) {
                this.preferences.last[section.key] = type;
                savePreferences(this.preferences);
                const entry = this.sectionButtons.get(section.key);
                entry.main.replaceChildren(icons.icon(type));
                entry.main.setAttribute('aria-label', toolName(type));
                entry.main.title = toolName(type);
            }
            if (type === 'cursor') this.manager.setMode('cursor');
            else this.manager.setMode(this.manager.mode === type ? 'cursor' : type);
        }

        updateRail() {
            const mode = this.manager.mode;
            this.sectionButtons.forEach(({main}, key) => {
                const section = SECTIONS.find(candidate => candidate.key === key);
                const active = section.groups.flatMap(groupItems).includes(mode);
                main.classList.toggle('active', active);
                main.setAttribute('aria-pressed', String(active));
            });
            const toggles = {
                measure: mode === 'measure', zoom: mode === 'zoom', magnet: this.manager.magnet !== 'off', stay: this.manager.stayInMode,
                lock: this.manager.lockedAll, hide: this.manager.hiddenAll, tree: Boolean(this.tree), favorites: this.preferences.showFavorites
            };
            Object.entries(toggles).forEach(([key, pressed]) => {
                this.utilityButtons[key].classList.toggle('active', pressed);
                this.utilityButtons[key].setAttribute('aria-pressed', String(pressed));
            });
            this.utilityButtons.magnet.title = i18n.t(`draw.rail.magnet.${this.manager.magnet}`);
            this.utilityButtons.hide.replaceChildren(icons.icon(this.manager.hiddenAll ? 'eyeOff' : 'eye'));
            this.utilityButtons.lock.replaceChildren(icons.icon(this.manager.lockedAll ? 'lock' : 'unlock'));
            this.utilityButtons.undo.disabled = !this.manager.undoStack.length;
            this.utilityButtons.redo.disabled = !this.manager.redoStack.length;
            this.utilityButtons.remove.disabled = !this.manager.drawings.length;
            this.host.dataset.drawingMode = mode;
        }

        toggleFlyout(section, wrapper) {
            if (this.flyout?.dataset.section === section.key) { this.closeFlyout(); return; }
            this.closeFlyout();
            const flyout = element('div', {className: 'draw-flyout', attrs: {role: 'menu', 'data-section': section.key,
                'aria-label': i18n.t(`draw.section.${section.key}`)}});
            const back = element('button', {
                className: 'draw-flyout-back', attrs: {type: 'button'},
                on: {click: () => { this.closeFlyout(); this.sectionButtons.get(section.key).more.focus(); }},
                children: [icons.icon('chevron', 14), element('span', {text: i18n.t('draw.rail.back')})]
            });
            flyout.append(back);
            section.groups.forEach(group => {
                flyout.append(element('div', {className: 'draw-flyout-heading', text: i18n.t(`draw.group.${group}`)}));
                groupItems(group).forEach(type => {
                    const definition = D.tool(type);
                    const favorite = this.preferences.favorites.includes(type);
                    const item = element('button', {
                        className: `draw-flyout-item${this.manager.mode === type ? ' active' : ''}`,
                        attrs: {type: 'button', role: 'menuitem'},
                        on: {click: () => this.activate(type)},
                        children: [icons.icon(type), element('span', {className: 'draw-flyout-name', text: toolName(type)}),
                            definition?.shortcut ? element('span', {className: 'draw-flyout-shortcut', text: shortcutLabel(definition.shortcut)}) : null]
                    });
                    const star = element('button', {
                        className: `draw-flyout-star${favorite ? ' active' : ''}`,
                        attrs: {type: 'button', 'aria-pressed': String(favorite), 'aria-label': i18n.t(favorite ? 'draw.rail.unfavorite' : 'draw.rail.favorite', toolName(type))},
                        on: {click: event => { event.stopPropagation(); this.toggleFavorite(type); this.closeFlyout(); this.toggleFlyout(section, wrapper); }}
                    });
                    star.append(icons.icon('star', 14));
                    flyout.append(element('div', {className: 'draw-flyout-row', children: [item, MODES.includes(type) ? null : star]}));
                });
            });
            // Outside the rail, which may scroll and would cut the list off; full screen shows only its own element.
            (document.fullscreenElement || document.body).append(flyout);
            this.flyout = flyout;
            this.placeFlyout(wrapper);
            this.sectionButtons.get(section.key).more.setAttribute('aria-expanded', 'true');
            flyout.querySelector('.draw-flyout-item')?.focus();
            flyout.addEventListener('keydown', event => {
                const entries = [...flyout.querySelectorAll('.draw-flyout-item')];
                const index = entries.indexOf(document.activeElement);
                if (event.key === 'ArrowDown') { event.preventDefault(); entries[(index + 1) % entries.length]?.focus(); }
                if (event.key === 'ArrowUp') { event.preventDefault(); entries[(index - 1 + entries.length) % entries.length]?.focus(); }
                if (event.key === 'Escape') { event.stopPropagation(); this.closeFlyout(); this.sectionButtons.get(section.key).more.focus(); }
            });
            this.flyoutCloser = event => {
                if (!wrapper.contains(event.target) && !flyout.contains(event.target)) this.closeFlyout();
            };
            // It is fixed to the window, so it closes when the page or the rail scrolls under it.
            this.flyoutScrollCloser = event => {
                if (!flyout.contains(event.target)) this.closeFlyout();
            };
            this.flyoutResizeCloser = () => this.closeFlyout();
            document.addEventListener('pointerdown', this.flyoutCloser, true);
            document.addEventListener('scroll', this.flyoutScrollCloser, true);
            window.addEventListener('resize', this.flyoutResizeCloser);
        }

        /** Beside the rail section, kept inside the window; phones show it as a sheet at the bottom instead (CSS). */
        placeFlyout(wrapper) {
            const flyout = this.flyout;
            if (window.matchMedia?.('(max-width: 768px)').matches) return;
            const anchor = wrapper.getBoundingClientRect();
            const box = flyout.getBoundingClientRect();
            const left = Math.min(anchor.right + 6, window.innerWidth - box.width - 8);
            const top = Math.min(anchor.top, window.innerHeight - box.height - 8);
            flyout.style.left = `${Math.max(8, left)}px`;
            flyout.style.top = `${Math.max(8, top)}px`;
        }

        closeFlyout() {
            if (!this.flyout) return;
            const key = this.flyout.dataset.section;
            this.flyout.remove();
            this.flyout = null;
            this.sectionButtons.get(key)?.more.setAttribute('aria-expanded', 'false');
            document.removeEventListener('pointerdown', this.flyoutCloser, true);
            document.removeEventListener('scroll', this.flyoutScrollCloser, true);
            window.removeEventListener('resize', this.flyoutResizeCloser);
        }

        toggleFavorite(type) {
            const favorites = this.preferences.favorites;
            this.preferences.favorites = favorites.includes(type) ? favorites.filter(item => item !== type) : [...favorites, type];
            savePreferences(this.preferences);
            this.renderFavorites();
        }

        buildFavorites() {
            this.favoritesBar = element('div', {className: 'draw-favorites', attrs: {role: 'toolbar', 'aria-label': i18n.t('draw.rail.favorites')}});
            this.host.append(this.favoritesBar);
            this.renderFavorites();
            this.disposers.push(this.manager.on('mode', () => this.renderFavorites()));
        }

        renderFavorites() {
            const favorites = this.preferences.favorites.filter(type => D.tool(type));
            this.favoritesBar.hidden = !this.preferences.showFavorites || !favorites.length;
            this.favoritesBar.replaceChildren(...favorites.map(type => {
                const node = button(toolName(type), type, () => this.activate(type), {attrs: {'aria-pressed': String(this.manager.mode === type)}});
                node.classList.toggle('active', this.manager.mode === type);
                return node;
            }));
        }

        confirmRemoveAll(anchor) {
            const rect = anchor.getBoundingClientRect();
            menu([
                {label: i18n.plural('draw.action.removeAllConfirm', this.manager.drawings.length, this.manager.drawings.length), icon: 'trash', danger: true,
                    action: () => this.manager.clearAll()},
                {label: i18n.t('common.cancel'), action: () => {}}
            ], rect.right + 6, rect.top, this);
        }

        // ---- toast

        buildToast() {
            this.toastNode = element('div', {className: 'draw-toast', attrs: {role: 'status', 'aria-live': 'polite'}});
            this.host.append(this.toastNode);
        }

        toast(message) {
            if (!this.toastNode) return;
            this.toastNode.textContent = message || '';
            this.toastNode.classList.toggle('visible', Boolean(message));
            window.clearTimeout(this.toastTimer);
            if (message) this.toastTimer = window.setTimeout(() => this.toastNode.classList.remove('visible'), 3500);
        }

        // ---- selection toolbar

        buildSelectionToolbar() {
            const bar = element('div', {className: 'draw-selection-toolbar', attrs: {role: 'toolbar', 'aria-label': i18n.t('draw.selection.label')}});
            bar.hidden = true;
            this.colorButton = element('button', {
                className: 'draw-color-button', attrs: {type: 'button', 'aria-label': i18n.t('draw.prop.color'), title: i18n.t('draw.prop.color')},
                on: {click: event => this.openColorPicker(event.currentTarget)}
            });
            this.widthSelect = element('select', {className: 'draw-select', attrs: {'aria-label': i18n.t('draw.prop.width'), title: i18n.t('draw.prop.width')},
                on: {change: () => this.applyToSelection(style => { style.width = Number(this.widthSelect.value); })}});
            [1, 2, 3, 4].forEach(width => this.widthSelect.append(element('option', {text: `${width}px`, attrs: {value: width}})));
            this.styleSelect = element('select', {className: 'draw-select', attrs: {'aria-label': i18n.t('draw.prop.lineStyle'), title: i18n.t('draw.prop.lineStyle')},
                on: {change: () => this.applyToSelection(style => { style.lineStyle = this.styleSelect.value; })}});
            ['solid', 'dashed', 'dotted'].forEach(value => this.styleSelect.append(element('option', {text: i18n.t(`draw.option.lineStyle.${value}`), attrs: {value}})));
            this.selectionButtons = {
                settings: button(i18n.t('draw.action.settings'), 'gear', () => this.openProperties(this.manager.selectedDrawings()[0]), {className: 'draw-tool-button'}),
                alert: button(i18n.t('draw.action.alert'), 'alert', () => this.requestAlert(this.manager.selectedDrawings()[0]), {className: 'draw-tool-button'}),
                lock: button(i18n.t('draw.action.lock'), 'lock', () => this.toggleLock(), {className: 'draw-tool-button'}),
                clone: button(i18n.t('draw.action.clone'), 'clone', () => this.manager.clone(), {className: 'draw-tool-button'}),
                more: button(i18n.t('draw.action.more'), 'more', event => {
                    const rect = event.currentTarget.getBoundingClientRect();
                    this.showContextMenu(this.manager.selectedDrawings()[0], rect.left, rect.bottom + 4);
                }, {className: 'draw-tool-button'}),
                remove: button(i18n.t('draw.action.delete'), 'trash', () => this.manager.deleteSelected(), {className: 'draw-tool-button danger'})
            };
            bar.append(this.colorButton, this.widthSelect, this.styleSelect, ...Object.values(this.selectionButtons));
            this.selectionToolbar = bar;
            this.host.append(bar);
        }

        updateSelectionToolbar() {
            const selected = this.manager.selectedDrawings();
            this.selectionToolbar.hidden = !selected.length || this.manager.mode !== 'cursor';
            if (!selected.length) return;
            const first = selected[0];
            const definition = D.tool(first.type);
            const style = {...(definition?.defaults || {}), ...(first.style || {})};
            this.colorButton.style.setProperty('--draw-swatch', style.color || '#2962ff');
            const hasLine = selected.every(drawing => (D.tool(drawing.type)?.properties || []).some(property => property.key === 'width'));
            const hasStyle = selected.every(drawing => (D.tool(drawing.type)?.properties || []).some(property => property.key === 'lineStyle'));
            this.widthSelect.hidden = !hasLine;
            this.styleSelect.hidden = !hasStyle;
            if (hasLine) this.widthSelect.value = String(style.width || 1);
            if (hasStyle) this.styleSelect.value = ['solid', 'dashed', 'dotted'].includes(style.lineStyle) ? style.lineStyle : 'solid';
            this.selectionButtons.settings.disabled = selected.length !== 1;
            this.selectionButtons.alert.hidden = !(this.manager.features.alerts && selected.length === 1 && ALERTABLE.has(first.type));
            const locked = selected.every(drawing => drawing.locked);
            this.selectionButtons.lock.replaceChildren(icons.icon(locked ? 'lock' : 'unlock'));
            this.selectionButtons.lock.setAttribute('aria-pressed', String(locked));
            this.selectionButtons.lock.title = i18n.t(locked ? 'draw.action.unlock' : 'draw.action.lock');
            this.positionSelectionToolbar();
        }

        positionSelectionToolbar() {
            if (this.selectionToolbar.hidden) return;
            const paneNode = this.manager.paneElement('price');
            if (!paneNode) return;
            const hostRect = this.host.getBoundingClientRect();
            const paneRect = paneNode.getBoundingClientRect();
            const width = this.selectionToolbar.offsetWidth || 300;
            this.selectionToolbar.style.left = `${Math.max(8, paneRect.left - hostRect.left + (paneRect.width - width) / 2)}px`;
            this.selectionToolbar.style.top = `${Math.max(6, paneRect.top - hostRect.top + 8)}px`;
        }

        applyToSelection(mutator) {
            const ids = this.manager.selectedDrawings().map(drawing => drawing.id);
            if (!ids.length) return;
            this.manager.change(() => ids.forEach(id => {
                const drawing = this.manager.find(id);
                if (!drawing) return;
                drawing.style = {...(drawing.style || {})};
                mutator(drawing.style, drawing);
            }));
        }

        toggleLock() {
            const selected = this.manager.selectedDrawings();
            const lock = !selected.every(drawing => drawing.locked);
            this.manager.change(() => selected.forEach(drawing => { drawing.locked = lock; }));
            this.updateSelectionToolbar();
        }

        openColorPicker(anchor) {
            closeContextMenu();
            const popover = element('div', {className: 'draw-context-menu draw-color-popover', attrs: {role: 'dialog', 'aria-label': i18n.t('draw.prop.color')}});
            const grid = element('div', {className: 'draw-color-grid'});
            PALETTE.forEach(color => {
                const swatch = element('button', {className: 'draw-color-swatch', attrs: {type: 'button', 'aria-label': color, title: color},
                    on: {click: () => { this.applyToSelection(style => { style.color = color; }); closeContextMenu(); }}});
                swatch.style.setProperty('--draw-swatch', color);
                grid.append(swatch);
            });
            const custom = element('input', {attrs: {type: 'color', 'aria-label': i18n.t('draw.action.customColor')},
                on: {change: event => { this.applyToSelection(style => { style.color = event.target.value; }); closeContextMenu(); }}});
            popover.append(grid, element('label', {className: 'draw-custom-color', children: [element('span', {text: i18n.t('draw.action.customColor')}), custom]}));
            (document.fullscreenElement || document.body).append(popover);
            const rect = anchor.getBoundingClientRect();
            popover.style.left = `${Math.min(rect.left, window.innerWidth - 220)}px`;
            popover.style.top = `${rect.bottom + 6}px`;
            contextMenu = popover;
            contextOwner = this;
            popover.querySelector('button')?.focus();
        }

        // ---- context menu

        showContextMenu(drawing, clientX, clientY) {
            if (!drawing) return;
            const position = drawing.type === 'longPosition' || drawing.type === 'shortPosition';
            menu([
                {label: i18n.t('draw.action.settings'), icon: 'gear', action: () => this.openProperties(drawing)},
                {label: i18n.t('draw.action.clone'), icon: 'clone', hint: 'Ctrl+D', action: () => { this.manager.select(drawing.id); this.manager.clone(); }},
                {label: i18n.t('draw.action.copy'), hint: 'Ctrl+C', action: () => { this.manager.select(drawing.id); this.manager.copy(); }},
                {label: i18n.t(drawing.locked ? 'draw.action.unlock' : 'draw.action.lock'), icon: drawing.locked ? 'unlock' : 'lock',
                    action: () => this.manager.update(drawing.id, target => { target.locked = !target.locked; })},
                {label: i18n.t('draw.action.hide'), icon: 'eyeOff', action: () => this.manager.update(drawing.id, target => { target.hidden = true; })},
                'separator',
                {label: i18n.t('draw.action.bringToFront'), action: () => this.manager.bringToFront([drawing.id])},
                {label: i18n.t('draw.action.sendToBack'), action: () => this.manager.sendToBack([drawing.id])},
                'separator',
                {label: i18n.t('draw.action.saveDefault'), action: () => {
                    this.manager.saveDefaultStyle(drawing.type, {...(drawing.style || {})});
                    this.toast(i18n.t('draw.status.defaultSaved', toolName(drawing.type)));
                }},
                {label: i18n.t('draw.action.saveTemplate'), action: () => this.saveTemplate(drawing)},
                {label: i18n.t('draw.action.applyTemplate'), action: () => this.chooseTemplate(drawing, clientX, clientY)},
                this.manager.features.alerts && ALERTABLE.has(drawing.type) ? 'separator' : null,
                this.manager.features.alerts && ALERTABLE.has(drawing.type)
                    ? {label: i18n.t('draw.action.alert'), icon: 'alert', action: () => this.requestAlert(drawing)} : null,
                this.manager.features.demoTrade && position
                    ? {label: i18n.t('draw.action.demoTrade'), icon: 'demoTrade', action: () => this.requestDemoTrade(drawing)} : null,
                'separator',
                {label: i18n.t('draw.action.delete'), icon: 'trash', hint: 'Del', danger: true, disabled: drawing.locked, action: () => this.manager.remove(drawing.id)}
            ], clientX, clientY, this);
        }

        requestAlert(drawing) {
            if (!drawing) return;
            this.manager.emit('alert-request', drawing);
        }

        requestDemoTrade(drawing) {
            const [entry, target, stop] = drawing.points;
            this.manager.emit('demo-trade', {side: drawing.type === 'longPosition' ? 'BUY' : 'SELL', entry: entry.price, target: target.price, stop: stop.price});
        }

        saveTemplate(drawing) {
            prompt(i18n.t('draw.dialog.templateName'), '', async name => {
                await templates.save(drawing.type, name, {...(drawing.style || {})});
                this.toast(i18n.t('draw.status.templateSaved', name));
            });
        }

        async chooseTemplate(drawing, clientX, clientY) {
            const list = await templates.list(drawing.type);
            if (!list.length) {
                this.toast(i18n.t('draw.status.noTemplates'));
                return;
            }
            menu([
                ...list.map(template => ({label: template.name, action: () => this.manager.update(drawing.id, target => {
                    target.style = {...(target.style || {}), ...template.style};
                })})),
                'separator',
                ...list.map(template => ({label: i18n.t('draw.action.deleteTemplate', template.name), danger: true,
                    action: () => templates.remove(drawing.type, template)}))
            ], clientX, clientY, this);
        }

        // ---- inline text editing

        editText(drawing) {
            const definition = D.tool(drawing.type);
            if (!definition) return;
            const paneElement = this.manager.paneElement(this.manager.paneOf(drawing) || 'price');
            if (!paneElement) return;
            const paneRect = paneElement.getBoundingClientRect();
            const hostRect = this.host.getBoundingClientRect();
            const env = this.manager.environment(this.manager.paneOf(drawing) || 'price', paneRect.width, paneRect.height);
            const anchor = env.point(drawing.points[drawing.type === 'callout' || drawing.type === 'priceNote' ? 1 : 0]);
            if (!anchor) return;
            this.closeTextEditor();
            const editor = element('textarea', {className: 'draw-text-editor', attrs: {rows: 2, maxlength: 2000, 'aria-label': i18n.t('draw.prop.text'),
                placeholder: i18n.t('draw.label.addText')}});
            editor.value = drawing.style?.text || '';
            editor.style.left = `${paneRect.left - hostRect.left + anchor.x}px`;
            editor.style.top = `${paneRect.top - hostRect.top + anchor.y - 18}px`;
            const original = editor.value;
            const commit = () => {
                if (!this.textEditor) return;
                const value = editor.value;
                this.closeTextEditor();
                if (value !== original) this.manager.update(drawing.id, target => { target.style = {...(target.style || {}), text: value}; });
            };
            editor.addEventListener('keydown', event => {
                event.stopPropagation();
                if (event.key === 'Escape') { this.closeTextEditor(); }
                if (event.key === 'Enter' && !event.shiftKey && drawing.type !== 'table') { event.preventDefault(); commit(); }
            });
            editor.addEventListener('blur', commit);
            this.host.append(editor);
            this.textEditor = editor;
            editor.focus();
        }

        closeTextEditor() {
            const editor = this.textEditor;
            this.textEditor = null;
            editor?.remove();
        }

        // ---- settings dialog

        openProperties(drawing) {
            if (!drawing) return;
            const definition = D.tool(drawing.type);
            if (!definition) return;
            const manager = this.manager;
            const before = manager.snapshot();
            const original = JSON.parse(JSON.stringify(drawing));
            const dialog = element('dialog', {className: 'draw-dialog draw-properties-dialog', attrs: {'aria-labelledby': 'drawPropertiesTitle'}});
            const content = element('form', {className: 'draw-dialog-content', attrs: {method: 'dialog'}});
            const title = element('h2', {className: 'draw-dialog-title', text: toolName(drawing.type), attrs: {id: 'drawPropertiesTitle'}});
            const close = button(i18n.t('common.close'), 'close', () => cancel(), {className: 'draw-dialog-close'});
            const live = mutator => {
                const target = manager.find(drawing.id);
                if (!target) return;
                target.style = {...(definition.defaults || {}), ...(target.style || {})};
                mutator(target);
                manager.requestRender();
            };
            const properties = definition.properties || [];
            const tabs = [];
            ['style', 'text', 'inputs'].forEach(tab => {
                const fields = properties.filter(property => (property.tab || 'style') === tab);
                if (fields.length) tabs.push({key: tab, build: () => fields.map(property => this.field(property, drawing, definition, live))});
            });
            if (!definition.screenAnchored) tabs.push({key: 'coordinates', build: () => this.coordinateFields(drawing, live)});
            tabs.push({key: 'visibility', build: () => this.visibilityFields(drawing, live)});
            const tabList = element('div', {className: 'draw-tabs', attrs: {role: 'tablist'}});
            const panel = element('div', {className: 'draw-tab-panel', attrs: {role: 'tabpanel'}});
            const select = key => {
                [...tabList.children].forEach(tab => {
                    const active = tab.dataset.tab === key;
                    tab.classList.toggle('active', active);
                    tab.setAttribute('aria-selected', String(active));
                    tab.tabIndex = active ? 0 : -1;
                });
                panel.replaceChildren(...tabs.find(tab => tab.key === key).build());
            };
            tabs.forEach(tab => tabList.append(element('button', {
                className: 'draw-tab', text: i18n.t(`draw.tab.${tab.key}`),
                attrs: {type: 'button', role: 'tab', 'data-tab': tab.key, 'aria-selected': 'false'},
                on: {click: () => select(tab.key)}
            })));
            tabList.addEventListener('keydown', event => {
                const entries = [...tabList.children];
                const index = entries.indexOf(document.activeElement);
                if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                    event.preventDefault();
                    const next = entries[(index + (event.key === 'ArrowRight' ? 1 : -1) + entries.length) % entries.length];
                    next.focus();
                    select(next.dataset.tab);
                }
            });
            const finish = commit => {
                if (!dialog.open) return;
                if (commit) manager.commit(before);
                dialog.close();
            };
            const cancel = () => {
                const index = manager.drawings.findIndex(item => item.id === drawing.id);
                if (index >= 0) manager.drawings[index] = original;
                manager.requestRender();
                finish(false);
            };
            const templateButton = element('button', {className: 'btn-secondary draw-template-button', text: i18n.t('draw.action.template'),
                attrs: {type: 'button'}, on: {click: event => {
                    const rect = event.currentTarget.getBoundingClientRect();
                    const target = manager.find(drawing.id) || drawing;
                    menu([
                        {label: i18n.t('draw.action.saveDefault'), action: () => {
                            manager.saveDefaultStyle(drawing.type, {...(target.style || {})});
                            this.toast(i18n.t('draw.status.defaultSaved', toolName(drawing.type)));
                        }},
                        {label: i18n.t('draw.action.resetDefault'), action: () => {
                            live(item => { item.style = {...(definition.defaults || {})}; });
                            select(tabList.querySelector('.active')?.dataset.tab || tabs[0].key);
                        }},
                        {label: i18n.t('draw.action.saveTemplate'), action: () => this.saveTemplate(target)},
                        {label: i18n.t('draw.action.applyTemplate'), action: () => this.chooseTemplate(target, rect.left, rect.top)}
                    ], rect.left, rect.bottom + 4, this);
                }}});
            content.append(element('div', {className: 'draw-dialog-heading', children: [title, close]}), tabList, panel, element('div', {
                className: 'draw-dialog-actions', children: [
                    templateButton,
                    element('span', {className: 'draw-dialog-spacer'}),
                    element('button', {className: 'btn-secondary', text: i18n.t('common.cancel'), attrs: {type: 'button'}, on: {click: cancel}}),
                    element('button', {className: 'btn-primary', text: i18n.t('draw.action.ok'), attrs: {type: 'submit'}})
                ]
            }));
            content.addEventListener('submit', event => { event.preventDefault(); finish(true); });
            dialog.addEventListener('cancel', event => { event.preventDefault(); cancel(); });
            dialog.addEventListener('close', () => dialog.remove());
            dialog.append(content);
            document.body.append(dialog);
            select(tabs[0].key);
            dialog.showModal();
        }

        field(property, drawing, definition, live) {
            const target = () => this.manager.find(drawing.id) || drawing;
            const current = () => ({...(definition.defaults || {}), ...(target().style || {})})[property.key];
            const label = i18n.t(property.label || `draw.prop.${property.key}`);
            const set = value => live(item => { item.style[property.key] = value; });
            const wrap = (control, inline = false) => element('label', {className: `draw-field${inline ? ' inline' : ''}`, children: [
                element('span', {className: 'draw-field-label', text: label}), control,
                property.hint ? element('small', {className: 'draw-field-hint', text: i18n.t(property.hint)}) : null]});
            switch (property.type) {
                case 'color': {
                    const input = element('input', {attrs: {type: 'color'}, on: {input: event => set(event.target.value)}});
                    input.value = /^#[0-9a-f]{6}$/i.test(current() || '') ? current() : '#2962ff';
                    return wrap(input, true);
                }
                case 'opacity': {
                    const input = element('input', {attrs: {type: 'range', min: 0, max: 1, step: 0.01}, on: {input: event => set(Number(event.target.value))}});
                    input.value = String(current() ?? 0.15);
                    return wrap(input);
                }
                case 'width': {
                    const input = element('select', {on: {change: event => set(Number(event.target.value))}});
                    [1, 2, 3, 4].forEach(width => input.append(element('option', {text: `${width}px`, attrs: {value: width}})));
                    input.value = String(current() || 1);
                    return wrap(input, true);
                }
                case 'lineStyle': {
                    const input = element('select', {on: {change: event => set(event.target.value)}});
                    ['solid', 'dashed', 'dotted', 'largeDashed', 'sparseDotted'].forEach(value =>
                        input.append(element('option', {text: i18n.t(`draw.option.lineStyle.${value}`), attrs: {value}})));
                    input.value = current() || 'solid';
                    return wrap(input, true);
                }
                case 'bool': {
                    const input = element('input', {attrs: {type: 'checkbox'}, on: {change: event => set(event.target.checked)}});
                    input.checked = Boolean(current());
                    return element('label', {className: 'draw-field draw-check', children: [input, element('span', {text: label})]});
                }
                case 'number': {
                    const input = element('input', {attrs: {type: 'number', min: property.min, max: property.max, step: property.step ?? 'any',
                        inputmode: 'decimal'}, on: {input: event => {
                        const value = Number(event.target.value);
                        if (Number.isFinite(value)) set(Math.min(property.max ?? Infinity, Math.max(property.min ?? -Infinity, value)));
                    }}});
                    input.value = String(current() ?? '');
                    return wrap(input, true);
                }
                case 'fontSize': {
                    const input = element('select', {on: {change: event => set(Number(event.target.value))}});
                    [10, 11, 12, 13, 14, 16, 20, 24, 28, 32, 40].forEach(size => input.append(element('option', {text: String(size), attrs: {value: size}})));
                    input.value = String(current() || 13);
                    return wrap(input, true);
                }
                case 'select': {
                    const input = element('select', {on: {change: event => set(event.target.value)}});
                    (property.options || []).forEach(value => input.append(element('option', {
                        text: property.literalOptions ? value : i18n.t(`draw.option.${property.key}.${value}`), attrs: {value}
                    })));
                    input.value = current() ?? property.options?.[0];
                    return wrap(input, true);
                }
                case 'text': {
                    const input = element('textarea', {attrs: {rows: 4, maxlength: 2000}, on: {input: event => set(event.target.value)}});
                    input.value = current() || '';
                    return wrap(input);
                }
                case 'levels': return this.levelsEditor(property, drawing, definition, live, label);
                default: return element('span');
            }
        }

        levelsEditor(property, drawing, definition, live, label) {
            const container = element('div', {className: 'draw-levels'});
            const levels = () => {
                const target = this.manager.find(drawing.id) || drawing;
                const value = (target.style || {}).levels || definition.defaults.levels || [];
                return value.map(level => ({...level}));
            };
            const render = () => {
                const rows = levels();
                container.replaceChildren(element('span', {className: 'draw-field-label', text: label}), ...rows.map((level, index) => {
                    const update = mutator => live(item => {
                        const next = levels();
                        mutator(next[index]);
                        item.style.levels = next;
                    });
                    const visible = element('input', {attrs: {type: 'checkbox', 'aria-label': i18n.t('draw.prop.levelVisible')},
                        on: {change: event => update(row => { row.visible = event.target.checked; })}});
                    visible.checked = level.visible !== false;
                    const value = element('input', {attrs: {type: 'number', step: 'any', 'aria-label': i18n.t('draw.prop.levelValue')},
                        on: {input: event => { const number = Number(event.target.value); if (Number.isFinite(number)) update(row => { row.value = number; }); }}});
                    value.value = String(level.value);
                    const color = element('input', {attrs: {type: 'color', 'aria-label': i18n.t('draw.prop.levelColor')},
                        on: {input: event => update(row => { row.color = event.target.value; })}});
                    color.value = /^#[0-9a-f]{6}$/i.test(level.color || '') ? level.color : '#787b86';
                    const remove = button(i18n.t('draw.action.removeLevel'), 'close', () => {
                        live(item => { const next = levels(); next.splice(index, 1); item.style.levels = next; });
                        render();
                    }, {className: 'draw-tool-button'});
                    return element('div', {className: 'draw-level-row', children: [visible, value, color, remove]});
                }), element('button', {className: 'btn-secondary draw-add-level', text: i18n.t('draw.action.addLevel'), attrs: {type: 'button'},
                    on: {click: () => {
                        live(item => { const next = levels(); next.push({value: 1.5, color: '#787b86', visible: true}); item.style.levels = next; });
                        render();
                    }}}));
            };
            render();
            return container;
        }

        coordinateFields(drawing, live) {
            const target = () => this.manager.find(drawing.id) || drawing;
            return target().points.map((point, index) => {
                const date = element('input', {attrs: {type: 'date', 'aria-label': i18n.t('draw.prop.pointDate', index + 1)},
                    on: {change: event => {
                        const seconds = D.toSeconds(event.target.value);
                        if (Number.isFinite(seconds)) live(item => { item.points[index] = {...item.points[index], time: seconds}; });
                    }}});
                date.value = new Date(point.time * 1000).toISOString().slice(0, 10);
                const price = element('input', {attrs: {type: 'number', step: 'any', inputmode: 'decimal', 'aria-label': i18n.t('draw.prop.pointPrice', index + 1)},
                    on: {input: event => {
                        const value = Number(event.target.value);
                        if (Number.isFinite(value)) live(item => { item.points[index] = {...item.points[index], price: value}; });
                    }}});
                price.value = String(Number(point.price.toPrecision(10)));
                return element('div', {className: 'draw-coordinate-row', children: [
                    element('span', {className: 'draw-field-label', text: i18n.t('draw.prop.point', index + 1)}), date, price]});
            });
        }

        visibilityFields(drawing, live) {
            const target = () => this.manager.find(drawing.id) || drawing;
            const fields = [element('p', {className: 'draw-field-hint', text: i18n.t('draw.prop.intervalsHint')})];
            INTERVALS.forEach(interval => {
                const input = element('input', {attrs: {type: 'checkbox'}, on: {change: () => live(item => {
                    const chosen = INTERVALS.filter(candidate => fields.find(node => node.dataset?.interval === candidate)?.querySelector('input').checked);
                    item.intervals = chosen.length === INTERVALS.length ? null : chosen;
                })}});
                const intervals = target().intervals;
                input.checked = !Array.isArray(intervals) || !intervals.length || intervals.includes(interval);
                const row = element('label', {className: 'draw-field draw-check', attrs: {'data-interval': interval},
                    children: [input, element('span', {text: i18n.t(`draw.interval.${interval}`)})]});
                fields.push(row);
            });
            const name = element('input', {attrs: {type: 'text', maxlength: 60, placeholder: toolName(drawing.type)},
                on: {input: event => live(item => { item.name = event.target.value.trim() || undefined; })}});
            name.value = target().name || '';
            fields.push(element('label', {className: 'draw-field', children: [element('span', {className: 'draw-field-label', text: i18n.t('draw.prop.name')}), name]}));
            return fields;
        }

        // ---- object tree

        toggleTree() {
            if (this.tree) {
                this.tree.close();
                return;
            }
            this.tree = new ObjectTree(this);
            this.updateRail();
        }

        destroy() {
            this.disposers.forEach(dispose => dispose());
            this.closeFlyout();
            this.closeTextEditor();
            this.tree?.close();
            this.rail?.remove();
            this.favoritesBar?.remove();
            this.selectionToolbar?.remove();
            this.toastNode?.remove();
            if (contextOwner === this) closeContextMenu();
            this.host.classList.remove('drawing-host');
        }
    }

    /** Lists drawings (top first) and, when an indicator manager registers a provider, indicators. */
    class ObjectTree {
        constructor(ui) {
            this.ui = ui;
            this.manager = ui.manager;
            this.panel = element('aside', {className: 'draw-tree-panel', attrs: {'aria-label': i18n.t('draw.rail.objectTree')}});
            const heading = element('div', {className: 'draw-tree-heading', children: [
                element('h3', {text: i18n.t('draw.rail.objectTree')}),
                button(i18n.t('common.close'), 'close', () => this.close(), {className: 'draw-tool-button'})
            ]});
            this.list = element('div', {className: 'draw-tree-list'});
            this.panel.append(heading, this.list);
            ui.host.append(this.panel);
            this.disposers = [this.manager.on('selection', () => this.refresh()), this.manager.on('indicators', () => this.refresh())];
            this.refresh();
        }

        refresh() {
            const manager = this.manager;
            const rows = [];
            const indicators = typeof manager.objectTreeProvider === 'function' ? manager.objectTreeProvider() : [];
            if (indicators.length) {
                rows.push(element('div', {className: 'draw-tree-section', text: i18n.t('draw.tree.indicators')}));
                indicators.forEach(item => rows.push(this.row({
                    label: item.label, icon: 'tree', visible: item.visible, onToggle: item.onToggle, onRemove: item.onRemove,
                    onSelect: item.onSettings, settings: item.onSettings
                })));
            }
            rows.push(element('div', {className: 'draw-tree-section', text: i18n.t('draw.tree.drawings')}));
            if (!manager.drawings.length) rows.push(element('p', {className: 'draw-tree-empty', text: i18n.t('draw.tree.empty')}));
            [...manager.drawings].reverse().forEach(drawing => rows.push(this.row({
                label: drawing.name || toolName(drawing.type),
                icon: drawing.type,
                visible: !drawing.hidden,
                locked: drawing.locked,
                selected: manager.selection.has(drawing.id),
                hiddenByInterval: !drawing.hidden && !manager.visible(drawing),
                onSelect: () => manager.select(drawing.id),
                onToggle: () => manager.update(drawing.id, target => { target.hidden = !target.hidden; }),
                onLock: () => manager.update(drawing.id, target => { target.locked = !target.locked; }),
                onRemove: () => manager.remove(drawing.id),
                onUp: () => manager.move(drawing.id, 1),
                onDown: () => manager.move(drawing.id, -1),
                settings: () => this.ui.openProperties(drawing)
            })));
            this.list.replaceChildren(...rows);
        }

        row(item) {
            const name = element('button', {className: 'draw-tree-name', attrs: {type: 'button'}, on: {click: () => item.onSelect?.(), dblclick: () => item.settings?.()},
                children: [icons.icon(item.icon, 16), element('span', {text: item.label})]});
            const controls = element('span', {className: 'draw-tree-controls'});
            if (item.onUp) controls.append(button(i18n.t('draw.action.moveUp'), 'chevron', item.onUp, {className: 'draw-tool-button draw-up'}));
            if (item.onDown) controls.append(button(i18n.t('draw.action.moveDown'), 'chevron', item.onDown, {className: 'draw-tool-button draw-down'}));
            if (item.onToggle) controls.append(button(i18n.t(item.visible ? 'draw.action.hide' : 'draw.action.show'), item.visible ? 'eye' : 'eyeOff', item.onToggle,
                {className: 'draw-tool-button', attrs: {'aria-pressed': String(!item.visible)}}));
            if (item.onLock) controls.append(button(i18n.t(item.locked ? 'draw.action.unlock' : 'draw.action.lock'), item.locked ? 'lock' : 'unlock', item.onLock,
                {className: 'draw-tool-button', attrs: {'aria-pressed': String(Boolean(item.locked))}}));
            if (item.settings) controls.append(button(i18n.t('draw.action.settings'), 'gear', item.settings, {className: 'draw-tool-button'}));
            if (item.onRemove) controls.append(button(i18n.t('draw.action.delete'), 'trash', item.onRemove, {className: 'draw-tool-button danger'}));
            const row = element('div', {className: `draw-tree-row${item.selected ? ' selected' : ''}${item.visible === false ? ' muted' : ''}`,
                children: [name, controls]});
            if (item.hiddenByInterval) row.title = i18n.t('draw.tree.otherInterval');
            return row;
        }

        close() {
            this.disposers.forEach(dispose => dispose());
            this.panel.remove();
            this.ui.tree = null;
            this.ui.updateRail();
        }
    }

    window.StockWatchDrawingUI = DrawingUI;
})();
