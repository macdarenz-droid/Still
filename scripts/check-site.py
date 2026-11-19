#!/usr/bin/env python3
"""Validate unpublished discovery assets without third-party dependencies."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit
import sys

ROOT = Path(__file__).resolve().parent.parent / 'apps' / 'site'


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tags = []
        self.ids = set()
        self.references = []
        self.errors = []
        self.title = ''
        self.in_title = False

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        self.tags.append((tag, attrs))
        if attrs.get('id'):
            if attrs['id'] in self.ids:
                self.errors.append('duplicate id: ' + attrs['id'])
            self.ids.add(attrs['id'])
        if tag == 'title':
            self.in_title = True
        if tag == 'img' and 'alt' not in attrs:
            self.errors.append('image missing alt text')
        if tag == 'form':
            self.errors.append('visitor data collection is outside this milestone')
        for key in ('href', 'src'):
            if attrs.get(key):
                self.references.append(attrs[key])

    def handle_endtag(self, tag):
        if tag == 'title':
            self.in_title = False

    def handle_data(self, value):
        if self.in_title:
            self.title += value


def main():
    path = ROOT / 'index.html'
    if not path.is_file():
        sys.exit('Missing discovery page: apps/site/index.html')
    html = path.read_text()
    page = Page()
    page.feed(html)
    errors = list(page.errors)
    tags = page.tags
    if not any(tag == 'html' and attrs.get('lang') == 'en' for tag, attrs in tags):
        errors.append('page must declare English language')
    if len([1 for tag, _ in tags if tag == 'h1']) != 1:
        errors.append('page must have exactly one h1')
    if not any(tag == 'main' for tag, _ in tags):
        errors.append('page must have a main landmark')
    if not (10 <= len(page.title.strip()) <= 90):
        errors.append('page needs a descriptive title')
    meta = {attrs.get('name', '').lower(): attrs.get('content', '')
            for tag, attrs in tags if tag == 'meta'}
    if len(meta.get('description', '').strip()) < 40:
        errors.append('page needs a meaningful meta description')
    if 'width=device-width' not in meta.get('viewport', ''):
        errors.append('page needs a device-width viewport')
    if 'noindex' not in meta.get('robots', '').lower():
        errors.append('unpublished preview must be marked noindex')
    if any(tag == 'link' and 'canonical' in attrs.get('rel', '').split()
           for tag, attrs in tags):
        errors.append('canonical domain must wait for a verified publication domain')
    for reference in page.references:
        target = urlsplit(reference)
        if target.scheme or target.netloc:
            if target.scheme and target.scheme not in ('https', 'mailto'):
                errors.append('unexpected URL scheme: ' + reference)
            continue
        if not target.path:
            if target.fragment and target.fragment not in page.ids:
                errors.append('unknown page anchor: ' + reference)
        else:
            local = (ROOT / target.path.lstrip('/')).resolve()
            if not local.is_relative_to(ROOT.resolve()) or not local.is_file():
                errors.append('missing or invalid local resource: ' + reference)
    robots = ROOT / 'robots.txt'
    if not robots.is_file() or 'Disallow: /' not in robots.read_text():
        errors.append('unpublished preview needs robots.txt Disallow: /')
    if errors:
        print('\n'.join('FAIL: ' + error for error in errors), file=sys.stderr)
        return 1
    print(f'Discovery checks passed ({len(page.references)} links/resources, noindex).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
