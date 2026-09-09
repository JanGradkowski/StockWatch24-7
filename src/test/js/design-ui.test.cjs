const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function element(properties = {}) {
    const listeners = new Map(), classes = new Set();
    return Object.assign({children: [], disabled: false,
        classList: {toggle(name, active) {active ? classes.add(name) : classes.delete(name);}, contains: name => classes.has(name)},
        append(...children) {this.children.push(...children);}, setAttribute() {}, focus() {},
        addEventListener(name, listener) {listeners.set(name, listener);},
        dispatchEvent(event) {listeners.get(event.type)?.(event);},
        querySelector: () => null
    }, properties);
}

function settings() {
    const form = element({action: 'https://example.test/settings/scoring'});
    const reset = element({type: 'submit', form: element()});
    const apply = element({type: 'submit', form});
    const points = element({name: 'points', type: 'number', value: '25', form});
    const included = element({name: 'included', type: 'checkbox', value: 'on', checked: true, form});
    // A reset button can be physically nested in this form but owned by another form.
    form.querySelector = selector => selector === 'button[type="submit"]' ? reset : null;
    form.elements = [points, included, apply];
    form.requestSubmit = submitter => {assert.equal(submitter.form, form); form.submitted = submitter;};
    const document = {querySelector: () => null, querySelectorAll: selector => selector === 'form' ? [form] : [],
        createElement: () => element(), addEventListener() {}};
    let notifyDisabled;
    const context = {document, location: {href: 'https://example.test/settings/scoring'}, URL, Event,
        MutationObserver: class {constructor(callback) {notifyDisabled = callback;} observe(target) {assert.equal(target, apply);}}};
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/design-ui.js', 'utf8'), context);
    const bar = form.children[0], [discard, save] = bar.children[1].children;
    return {form, points, included, apply, bar, discard, save, notifyDisabled,
        change() {form.dispatchEvent(new Event('input'));}};
}

test('settings apply submits the owning preferences form and mirrors its validation state', () => {
    const ui = settings();
    assert.equal(ui.bar.hidden, true);
    ui.points.value = '30'; ui.change();
    assert.equal(ui.bar.hidden, false);
    assert.equal(ui.form.classList.contains('design-has-changes'), true);
    ui.apply.disabled = true; ui.notifyDisabled();
    assert.equal(ui.save.disabled, true);
    ui.apply.disabled = false; ui.notifyDisabled();
    assert.equal(ui.save.disabled, false);
    ui.save.dispatchEvent(new Event('click'));
    assert.equal(ui.form.submitted, ui.apply);
});

test('discard restores numeric and checkbox values and clears the dirty state', () => {
    const ui = settings();
    ui.points.value = '50'; ui.included.checked = false; ui.change();
    assert.equal(ui.bar.hidden, false);
    ui.discard.dispatchEvent(new Event('click'));
    assert.equal(ui.points.value, '25');
    assert.equal(ui.included.checked, true);
    assert.equal(ui.bar.hidden, true);
    assert.equal(ui.form.classList.contains('design-has-changes'), false);
});
