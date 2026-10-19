# Discovery and publication readiness

The Android app is the primary product. `apps/site` is a dependency-free local
product preview, with no analytics, forms, signup, downloads, external assets or
media uploads. It has no JavaScript. Its only external destination is the actual
project repository: <https://github.com/macdarenz-droid/Still>.

## Current preview

- Visible status: native Android, early development, no public release yet.
- Roadmap features are explicitly labelled planned and unavailable.
- Title and description identify the product and its development state.
- HTML is semantic, has a skip link and visible keyboard focus, and uses fluid
  layouts with reduced-motion support. Illustrations are labelled as design
  direction rather than app screenshots.
- `meta name="robots"` is `noindex, nofollow`; `robots.txt` disallows all paths.
- No canonical URL, sitemap, social preview URL, app-store URL, product rating or
  structured availability is invented before a real destination exists.
- Working name and branding remain provisional. This preview is unpublished.

For a local preview from the repository root:

```sh
python3 -m http.server 8080 --directory apps/site --bind 127.0.0.1
```

Open `http://127.0.0.1:8080`. This starts a local server only; it does not publish
the page. Run the supervisor-owned `npm run check:site` for static acceptance.
Browser verification should cover 320, 390, 768 and 1440 CSS-pixel widths, keyboard
navigation, enlarged text, reduced motion, in-page navigation and the absence of
horizontal overflow. Automated checks do not replace screen-reader review.

## Before any public website

1. Owner confirms the working name after app-store, intended-market trademark and
   domain checks; approve final accessible logo artwork.
2. Select and verify an owned HTTPS domain and hosting destination. Configure
   explicit response headers, including a restrictive Content Security Policy,
   and test routing for the HTML, stylesheet, favicon and robots file.
3. Review every public claim against the actual release state. Change the
   "no public app release" text only after publication has been verified; add a
   download link only when the real destination works.
4. Provide an owner-approved support destination and app privacy policy when
   relevant. Do not invent legal, support or social links. The design-rules
   section is explanatory copy, not a substitute for a privacy policy.
5. Independently review the candidate, complete a red-team pass and resolve any
   blockers. Check contrast, keyboard/screen-reader behaviour, text at 200% and
   400%, responsive layout, reduced motion, links and content accuracy.
6. Obtain owner-controlled publication authorization. No build or review implies
   permission to publish.
7. Verify the published URL and response behaviour after an authorized release.

## Before search indexing

Keep both indexing restrictions until there is an approved public destination.
`robots.txt` is not access control and does not keep a publicly hosted preview
private; no sensitive data belongs in these files.

When a real domain and public content are approved, replace the preview indexing
rules deliberately. Set an absolute HTTPS canonical on the chosen public URL and
check any redirects. Use robots rules appropriate to the approved pages, and add
a sitemap only with existing public URLs. If an already reachable preview must
leave search results, allow crawlers to fetch its `noindex` directive; a blanket
robots disallow can prevent that fetch. Review this migration before publication.

Do not add review/rating markup, download counts, app-store availability, prices
or claims that have not been measured or verified. Structured data and social
metadata can follow once real destinations and approved assets exist.

## Before Android store submission

Use the [listing draft](android-store-draft.md) only after the final candidate's
behaviour is accepted. Independently review the final binary and complete a
red-team pass. Device testing must verify media permission transitions, selected
access, revocation, performance, sharing, viewer behaviour and accessibility.

Validate support and privacy destinations, exact Data safety disclosures, content
rating, photo/video permission eligibility, package identity and screenshots.
The owner controls production signing, secrets, submission, provider connections,
spending and publication. A debug APK is not a store release.
