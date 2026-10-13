// Every learning-mode marker in the templates and scripts names an explanation that exists in both
// languages: data-term="id" needs js.glossary.<id>.term and .text, data-learn="key" needs js.learn.<key>.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const TEMPLATES = 'src/main/resources/templates';
const SCRIPTS = 'src/main/resources/static/js';
const ENUMS = 'src/main/java/org/example/stockwatch247/model/enums';

function files(directory, extension) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) return files(full, extension);
    return full.endsWith(extension) ? [full] : [];
  });
}

function keys(bundle) {
  return new Set(fs.readFileSync(`src/main/resources/${bundle}`, 'utf8').split(/\r?\n/)
    .filter(line => line && !/^\s*[#!]/.test(line) && line.includes('='))
    .map(line => line.slice(0, line.indexOf('=')).trim()));
}

function constants(enumFile) {
  const body = fs.readFileSync(path.join(ENUMS, enumFile), 'utf8').split('{')[1].split(';')[0];
  return [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)\b/gm)].map(match => match[1]);
}

// Markers whose id is computed in a template (pattern titles, lifecycle pills) use these ids.
function computedTerms() {
  const patterns = constants('CandlePattern.java').filter(name => name !== 'ANY')
    .map(name => name.startsWith('ELLIOTT_')
      ? 'elliott.' + name.replace(/^ELLIOTT_(BULLISH|BEARISH)_/, '')
      : 'pattern.' + name);
  const harmonics = constants('HarmonicPatternType.java').map(name => 'pattern.HARMONIC_' + name);
  const statuses = constants('SignalLifecycleStatus.java')
    .map(name => 'status' + name.charAt(0) + name.slice(1).toLowerCase());
  return [...patterns, ...harmonics, ...statuses];
}

function referenced() {
  const terms = new Set(computedTerms());
  const options = new Set();
  for (const file of [...files(TEMPLATES, '.html'), ...files(SCRIPTS, '.js')]) {
    const source = fs.readFileSync(file, 'utf8')
      .replace(/<!--[\s\S]*?-->/g, '').replace(/^\s*\/\/.*$/gm, '');
    for (const [, id] of source.matchAll(/data-term="([A-Za-z0-9_.]+)"/g)) terms.add(id);
    for (const [, id] of source.matchAll(/termHelp\('([A-Za-z0-9_.]+)'\)/g)) terms.add(id);
    for (const [, first, second] of source.matchAll(/data-term=\$\{[^}]*\?\s*'([A-Za-z0-9_.]+)'\s*:\s*'([A-Za-z0-9_.]+)'/g)) {
      terms.add(first);
      terms.add(second);
    }
    for (const [, id] of source.matchAll(/#\{js\.glossary\.([A-Za-z0-9_.]+)\.text\}/g)) terms.add(id);
    // Scripts that mark data with explanations list the ids in a GLOSSARY_TERMS map.
    for (const [, map] of source.matchAll(/GLOSSARY_TERMS = \{([^}]*)\}/g)) {
      for (const [, id] of map.matchAll(/'([A-Za-z0-9_.]+)'/g)) terms.add(id);
    }
    for (const [, key] of source.matchAll(/data-learn="([A-Za-z0-9_.]+)"/g)) options.add(key);
    for (const [, key] of source.matchAll(/learnHelp\('([A-Za-z0-9_.]+)'/g)) options.add(key);
  }
  return { terms, options };
}

test('every learning-mode term and option has an explanation in English and Polish', () => {
  const { terms, options } = referenced();
  assert.ok(terms.size > 100, 'templates mark the glossary terms');
  assert.ok(options.size > 40, 'templates mark the options');
  for (const bundle of ['messages.properties', 'messages_pl.properties']) {
    const available = keys(bundle);
    const missing = [
      ...[...terms].flatMap(id => [`js.glossary.${id}.term`, `js.glossary.${id}.text`]),
      ...[...options].map(key => `js.learn.${key}`)
    ].filter(key => !available.has(key));
    assert.deepEqual(missing, [], `missing in ${bundle}`);
  }
});

test('every explanation in the bundle is used somewhere, so none goes stale', () => {
  const { terms, options } = referenced();
  const available = [...keys('messages.properties')];
  const unusedTerms = available.filter(key => key.startsWith('js.glossary.') && key.endsWith('.text'))
    .map(key => key.slice('js.glossary.'.length, -'.text'.length))
    .filter(id => !terms.has(id));
  const unusedOptions = available.filter(key => key.startsWith('js.learn.'))
    .map(key => key.slice('js.learn.'.length))
    .filter(key => !options.has(key));
  assert.deepEqual(unusedTerms, []);
  assert.deepEqual(unusedOptions, []);
});

test('the About page glossary reads the same definitions that learning mode shows', () => {
  const about = fs.readFileSync(path.join(TEMPLATES, 'about.html'), 'utf8');
  const shown = [...about.matchAll(/#\{js\.glossary\.([A-Za-z]+)\.text\}/g)].map(match => match[1]);
  assert.deepEqual(shown, ['watchlist', 'follow', 'signal', 'tickerAlert', 'outlook', 'alert', 'setupScore',
    'tradeOutcome', 'signalStatus', 'demoTrade']);
});
