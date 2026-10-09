// English StockWatchI18n for script tests, built from the real "js." keys in messages.properties, so a
// test fails when a script asks for a key that does not exist.
const fs = require('node:fs');

function load(path) {
  const messages = {};
  for (const raw of fs.readFileSync(path, 'utf8').split(/\r?\n/)) {
    if (!raw || /^\s*[#!]/.test(raw) || !raw.includes('=')) continue;
    const index = raw.indexOf('=');
    const key = raw.slice(0, index).trim();
    if (key.startsWith('js.')) messages[key.slice(3)] = raw.slice(index + 1).replace(/\\(.)/g, '$1');
  }
  return messages;
}

function create(path = 'src/main/resources/messages.properties') {
  const messages = load(path);
  return Object.freeze({
    locale: 'en',
    tag: 'en',
    has(key) { return Object.prototype.hasOwnProperty.call(messages, key); },
    t(key, ...args) {
      if (!Object.prototype.hasOwnProperty.call(messages, key)) throw new Error(`Missing translation key js.${key}`);
      const message = messages[key];
      if (!args.length) return message;
      return message.replace(/''/g, '\u0000')
        .replace(/\{(\d+)\}/g, (match, index) => (args[Number(index)] ?? match).toString())
        .replace(/\u0000/g, "'");
    },
    plural(key, count, ...args) {
      const category = new Intl.PluralRules('en').select(Number(count));
      return this.t(Object.prototype.hasOwnProperty.call(messages, `${key}.${category}`) ? `${key}.${category}` : `${key}.other`, ...args);
    },
    number(value, digits) { return Number(value).toFixed(digits); },
    date(value, options) { return new Intl.DateTimeFormat('en', options).format(new Date(value)); }
  });
}

module.exports = { StockWatchI18n: create(), createI18n: create };
