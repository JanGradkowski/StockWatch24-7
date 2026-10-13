/* The browser checks serve /i18n/messages.js themselves (they run without the server): the "js." messages of
 * a bundle, behind the same window.StockWatchI18n API as I18nScriptController. */
const fs = require('node:fs');
const path = require('node:path');

const repo = path.resolve(__dirname, '..');

/** The "js." messages as /i18n/messages.js serves them (same API as I18nScriptController). */
function messages(bundle) {
  const entries = {};
  for (const raw of fs.readFileSync(path.join(repo, 'src/main/resources', bundle), 'utf8').split(/\r?\n/)) {
    if (!raw || /^\s*[#!]/.test(raw) || !raw.includes('=')) continue;
    const key = raw.slice(0, raw.indexOf('=')).trim();
    if (key.startsWith('js.')) entries[key.slice(3)] = raw.slice(raw.indexOf('=') + 1).replace(/\\(.)/g, '$1');
  }
  return entries;
}

function messagesScript(entries, tag) {
  return `(function () {
  var messages = ${JSON.stringify(entries)};
  var tag = ${JSON.stringify(tag)};
  function format(message, args) {
    if (!args.length) return message;
    return message.replace(/''/g, '\\u0000').replace(/\\{(\\d+)\\}/g, function (match, index) {
      var value = args[Number(index)];
      return value === undefined || value === null ? match : String(value);
    }).replace(/\\u0000/g, "'");
  }
  // Same formatter cache as I18nScriptController, so frame timings match the real page.
  var formatters = {};
  function formatter(kind, options) {
    var key = kind + JSON.stringify(options || {});
    if (!formatters[key]) formatters[key] = kind === 'number' ? new Intl.NumberFormat(tag, options) : new Intl.DateTimeFormat(tag, options);
    return formatters[key];
  }
  window.StockWatchI18n = Object.freeze({
    locale: tag, tag: tag,
    has: function (key) { return Object.prototype.hasOwnProperty.call(messages, key); },
    t: function (key) { var m = Object.prototype.hasOwnProperty.call(messages, key) ? messages[key] : key; return format(m, Array.prototype.slice.call(arguments, 1)); },
    plural: function (key, count) {
      var full = key + '.' + new Intl.PluralRules(tag).select(Number(count));
      if (!Object.prototype.hasOwnProperty.call(messages, full)) full = key + '.other';
      var m = Object.prototype.hasOwnProperty.call(messages, full) ? messages[full] : key;
      return format(m, Array.prototype.slice.call(arguments, 2));
    },
    number: function (value, digits) {
      var n = Number(value);
      if (!Number.isFinite(n)) return '';
      return tag === 'en' ? n.toFixed(digits) : formatter('number', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(n);
    },
    date: function (value, options) { return formatter('date', options).format(value instanceof Date ? value : new Date(value)); }
  });
})();`;
}

module.exports = { messages, messagesScript };
