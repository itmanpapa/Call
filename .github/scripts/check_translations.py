#!/usr/bin/env python3
"""Checks the string resources of the app.

Errors (exit code 1):
  * an XML file in res/values*/ is not well-formed;
  * a name is defined twice within one locale (across all files of the folder);
  * format placeholders of a translation differ from the default (values/);
  * a translated plural has no "other" item;
  * an unescaped apostrophe or quote that aapt2 would reject or strip.

Warnings: names that do not exist in the default resources, translations of
translatable="false" strings.

Info: names that are missing in a locale (falls back to English at runtime).

Usage: check_translations.py [path/to/res] [--verbose]
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

RES_DEFAULT = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'res')
TAGS = ('string', 'plurals', 'string-array')

# %[argument_index$][flags][width][.precision]conversion
PLACEHOLDER = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.\d+)?([a-zA-Z%])')


def element_text(el):
    """Text content of an element including nested markup (e.g. <b>, <xliff:g>)."""
    return ''.join(el.itertext())


def placeholders(text):
    """Returns a sorted list of (index, conversion) pairs; implicit indexes are numbered in order."""
    result = []
    implicit = 0
    for m in PLACEHOLDER.finditer(text):
        conv = m.group(4)
        if conv in ('%', 'n'):
            continue
        if m.group(1):
            index = int(m.group(1))
        else:
            implicit += 1
            index = implicit
        result.append((index, conv.lower() if conv in 'sS' else conv))
    return sorted(set(result))


def quote_problems(raw):
    """Finds apostrophes/quotes that aapt2 rejects. raw is the text as written in the XML."""
    text = raw.strip()
    if text.startswith('"') and text.endswith('"') and len(text) >= 2:
        return []  # the whole string is quoted, apostrophes inside are fine
    problems = []
    escaped = False
    for ch in text:
        if escaped:
            escaped = False
            continue
        if ch == '\\':
            escaped = True
        elif ch == "'":
            problems.append("unescaped apostrophe (use \\')")
            break
        elif ch == '"':
            problems.append('unescaped double quote (use \\" or typographic quotes)')
            break
    return problems


def raw_texts(path):
    """Maps (tag, name, quantity) to the raw inner XML of the item, for escape checks."""
    with open(path, encoding='utf-8') as f:
        content = f.read()
    content = re.sub(r'<!--.*?-->', '', content, flags=re.S)
    content = re.sub(r'<!\[CDATA\[.*?\]\]>', '', content, flags=re.S)
    out = {}
    for m in re.finditer(r'<string\s+[^>]*name="([^"]+)"[^>]*?(?<!/)>(.*?)</string>', content, re.S):
        out[('string', m.group(1), None)] = m.group(2)
    for m in re.finditer(r'<plurals\s+[^>]*name="([^"]+)"[^>]*>(.*?)</plurals>', content, re.S):
        for im in re.finditer(r'<item\s+quantity="([^"]+)"\s*>(.*?)</item>', m.group(2), re.S):
            out[('plurals', m.group(1), im.group(1))] = im.group(2)
    return out


class Locale:
    def __init__(self, folder):
        self.folder = folder
        self.items = {}  # name -> (tag, element, file)


def load(res, report):
    locales = {}
    for folder in sorted(glob.glob(os.path.join(res, 'values*'))):
        if not os.path.isdir(folder):
            continue
        loc = Locale(os.path.basename(folder))
        for path in sorted(glob.glob(os.path.join(folder, '*.xml'))):
            rel = os.path.relpath(path, res)
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as e:
                report.error(rel, 'malformed XML: %s' % e)
                continue
            has_strings = False
            raws = None
            for el in root:
                if el.tag not in TAGS:
                    continue
                has_strings = True
                name = el.get('name')
                if name in loc.items:
                    report.error(rel, 'duplicate name "%s" (already in %s)' % (name, loc.items[name][2]))
                    continue
                loc.items[name] = (el.tag, el, rel)
                if raws is None:
                    raws = raw_texts(path)
                if el.tag == 'string':
                    for p in quote_problems(raws.get(('string', name, None), '')):
                        report.error(rel, '"%s": %s' % (name, p))
                elif el.tag == 'plurals':
                    for item in el:
                        q = item.get('quantity')
                        for p in quote_problems(raws.get(('plurals', name, q), '')):
                            report.error(rel, '"%s" [%s]: %s' % (name, q, p))
            if has_strings:
                locales[loc.folder] = loc
    return locales


def is_translatable(tag, el):
    if el.get('translatable') == 'false':
        return False
    if tag == 'string-array':
        # Arrays of values or of @string references are not translated.
        if (el.get('name') or '').endswith('values'):
            return False
        items = [element_text(i).strip() for i in el]
        if all(i.startswith('@') for i in items):
            return False
    return True


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.infos = []

    def error(self, where, msg):
        self.errors.append('%s: %s' % (where, msg))

    def warning(self, where, msg):
        self.warnings.append('%s: %s' % (where, msg))

    def info(self, where, msg):
        self.infos.append('%s: %s' % (where, msg))


def check(res, verbose=False):
    report = Report()
    locales = load(res, report)
    default = locales.get('values')
    if default is None:
        report.error('values', 'default resources not found')
        return report
    translatable = {n: v for n, v in default.items.items() if is_translatable(v[0], v[1])}

    for folder, loc in locales.items():
        if folder == 'values':
            continue
        missing = []
        for name, (tag, el, rel) in loc.items.items():
            base = default.items.get(name)
            if base is None:
                report.warning(rel, '"%s" is not defined in the default resources' % name)
                continue
            btag, bel, _ = base
            if btag != tag:
                report.error(rel, '"%s" is a <%s> here but a <%s> in the default' % (name, tag, btag))
                continue
            if bel.get('translatable') == 'false':
                report.warning(rel, '"%s" is translatable="false" in the default' % name)
            if tag == 'string':
                exp = placeholders(element_text(bel))
                got = placeholders(element_text(el))
                if exp != got:
                    report.error(rel, '"%s": placeholders %s, default has %s' % (name, got, exp))
            elif tag == 'plurals':
                quantities = [i.get('quantity') for i in el]
                if 'other' not in quantities:
                    report.error(rel, '"%s": plural has no "other" item' % name)
                allowed = set()
                for i in bel:
                    allowed.update(placeholders(element_text(i)))
                for i in el:
                    got = set(placeholders(element_text(i)))
                    if not got <= allowed:
                        report.error(rel, '"%s" [%s]: placeholders %s not in default %s'
                                     % (name, i.get('quantity'), sorted(got - allowed), sorted(allowed)))
                    if i.get('quantity') == 'other' and got != allowed:
                        report.error(rel, '"%s" [other]: placeholders %s, default has %s'
                                     % (name, sorted(got), sorted(allowed)))
        for name in translatable:
            if name not in loc.items:
                missing.append(name)
        if missing:
            msg = '%d of %d strings missing' % (len(missing), len(translatable))
            if verbose:
                msg += ': ' + ', '.join(missing)
            report.info(folder, msg)
    return report


def main(argv):
    verbose = '--verbose' in argv
    args = [a for a in argv if not a.startswith('--')]
    res = os.path.abspath(args[0] if args else RES_DEFAULT)
    report = check(res, verbose)
    for line in report.infos:
        print('INFO    ' + line)
    for line in report.warnings:
        print('WARNING ' + line)
    for line in report.errors:
        print('ERROR   ' + line)
    print('%d error(s), %d warning(s), %d locale(s) with missing strings'
          % (len(report.errors), len(report.warnings), len(report.infos)))
    return 1 if report.errors else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
