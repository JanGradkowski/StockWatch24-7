// Every visible text in the templates and scripts comes from the message bundles, so Polish pages never
// show English. Text inside an element that Thymeleaf replaces (th:text, th:utext, th:replace, …) is only
// a design-time placeholder; scripts set visible text through window.StockWatchI18n.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const TEMPLATES = 'src/main/resources/templates';
const SCRIPTS = 'src/main/resources/static/js';
const REPLACING = ['th:text', 'th:utext', 'th:replace', 'th:insert', 'th:remove'];
const ATTRIBUTES = ['placeholder', 'title', 'aria-label', 'alt'];
// Names, symbols and samples that are the same in every language.
const UNTRANSLATED = new Set(['StockWatch', 'StockWatch 24/7', 'English', 'Polski', 'NVIDIA Corporation', 'Jan', 'Kowalski']);

function files(directory, extension) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) return files(full, extension);
    return full.endsWith(extension) ? [full] : [];
  });
}

function wordy(text) {
  const value = text.replace(/\s+/g, ' ').trim();
  if (!/[A-Za-z]{2}/.test(value) || UNTRANSLATED.has(value)) return null;
  if (/^[A-Z0-9.^=&/\-]{1,12}$/.test(value) || value.startsWith('©') || value.startsWith('[[')) return null;
  return value;
}

function replaced(element) {
  for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
    if (REPLACING.some(name => node.hasAttribute(name))) return true;
    if (['SCRIPT', 'STYLE', 'TEMPLATE'].includes(node.tagName)) return true;
  }
  return false;
}

function untranslatedInTemplate(file) {
  const html = fs.readFileSync(file, 'utf8');
  const { document } = new JSDOM(html).window;
  const found = [];
  const walker = document.createTreeWalker(document.documentElement, 4);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = wordy(node.textContent);
    if (text && !replaced(node.parentElement)) found.push(`text: ${text}`);
  }
  for (const element of document.querySelectorAll('*')) {
    for (const name of ATTRIBUTES) {
      if (!element.hasAttribute(name) || element.hasAttribute(`th:${name}`)) continue;
      const thAttr = element.getAttribute('th:attr') || '';
      if (thAttr.includes(`${name}=`)) continue;
      const value = wordy(element.getAttribute(name));
      if (value) found.push(`@${name}: ${value}`);
    }
  }
  return found;
}

// A literal English sentence or word assigned to something the user sees.
const VISIBLE_ASSIGNMENT = /\.(?:textContent|innerText|title|placeholder)\s*=\s*(['"`])([^'"`]*[A-Za-z]{3}[^'"`]*)\1|setAttribute\(\s*'(?:aria-label|title|placeholder)'\s*,\s*(['"`])([^'"`]*[A-Za-z]{3}[^'"`]*)\3|\b(?:alert|confirm)\(\s*(['"`])([^'"`]*[A-Za-z]{3}[^'"`]*)\5/g;

function untranslatedInScript(file) {
  const source = file.endsWith('.html')
    ? [...fs.readFileSync(file, 'utf8').matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]).join('\n')
    : fs.readFileSync(file, 'utf8');
  const found = [];
  for (const match of source.matchAll(VISIBLE_ASSIGNMENT)) {
    const text = (match[2] ?? match[4] ?? match[6]).trim();
    // Interpolated values are not literal text, and abbreviations (RSI, EMA) read the same in Polish.
    const literal = text.replace(/\$\{[^}]*\}/g, '').replace(/\$\{.*$/, '');
    if (!/[a-z]{3}/.test(literal)) continue;
    if (wordy(text)) found.push(text);
  }
  return found;
}

test('templates show only bundle text (outside elements Thymeleaf replaces)', () => {
  const problems = files(TEMPLATES, '.html').flatMap(file => untranslatedInTemplate(file).map(item => `${file}: ${item}`));
  assert.deepEqual(problems, [], 'move these texts into messages.properties and messages_pl.properties');
});

test('scripts set visible text through StockWatchI18n', () => {
  const sources = [...files(SCRIPTS, '.js'), ...files(TEMPLATES, '.html')];
  const problems = sources.flatMap(file => untranslatedInScript(file).map(text => `${file}: ${text}`));
  assert.deepEqual(problems, [], 'use i18n.t(...) with a js.* message key');
});
