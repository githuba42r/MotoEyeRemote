# SEO review — motoeye-remote.a42r.com

Reviewed 2026-10-24, at launch. Site source: `website/` (static HTML, deployed to
`/opt/apps/motoeye-remote/website` on the Oracle VM by `.github/workflows/deploy-website.yml`).

## Summary

| Check | Result |
|---|---|
| Lighthouse (index, setup, privacy) | **100 / 100 / 100 / 100** (performance, accessibility, best practices, SEO) on all three pages |
| Indexability | `robots.txt` allows all and points to the sitemap; every page has a canonical URL; 404 page is `noindex` and returns a real 404 status |
| HTTPS | HTTP → HTTPS 301; valid Let's Encrypt cert (DNS-01, auto-renew); proxied through Cloudflare |
| Page weight | Home page about 165 KB in total (14 KB HTML); screenshots are WebP at 14–39 KB each; no web fonts, no third-party scripts |
| Structured data | `WebSite` + `Person` + `MobileApplication` (home), `HowTo` + `BreadcrumbList` (setup); all valid JSON-LD |
| Social cards | Open Graph and Twitter card on all pages; a dedicated 1200×630 `og-image.jpg` |

The site launches technically clean. What's left is mostly off-page: discovery, links, and the Play listing.

## On-page audit

| Page | Title (chars) | Description (chars) | H1 | Words | Target query |
|---|---|---|---|---|---|
| `/` | MotoEye Remote — full-screen phone remote for the MotoEye E6 HUD (64) | 155 | 1 | ~700 | "MotoEye E6 remote", "MotoEye HUD app" |
| `/setup` | MotoEye E6 HUD remote setup — MotoEye Remote (44) | 150 | 1 | ~830 | "MotoEye E6 pair phone", "MotoEye remote not connecting" |
| `/privacy` | Privacy policy — MotoEye Remote (31) | 151 | 1 | ~650 | (Play policy URL, not a search target) |

### Fixed during the review

1. **Meta descriptions were 175–192 characters.** Google truncates them at about 155–160, so all three were rewritten to 150–155 with the key terms (MotoEye E6, glove-friendly, TPMS) first.
2. **Setup page title was 75 characters.** It's now 44 and leads with the query phrase "MotoEye E6 HUD remote setup".
3. **Hero image stacking.** On desktop the secondary screenshot covered the main remote screenshot, which is the LCP image. Fixed with `z-index`. The main image is now preloaded with `fetchpriority="high"`.
4. **Duplicate JPG screenshots** were taken out of `website/images/`, so only the WebP files ship.
5. **Server-side gaps flagged by Lighthouse** (no compression, short cache lifetimes) are handled in the nginx vhost: gzip for CSS/SVG/XML/manifest/JSON-LD, 30-day expiry on images and 7 days on CSS. HTML keeps revalidation, so content changes show up straight away.

### Content strengths

- Real device screenshots with descriptive alt text, listed in the sitemap as image entries.
- The setup page answers the practical searches (pairing, CAM/REC not working, disconnects with the screen off, no TPMS reading) in a troubleshooting section.
- The permissions table explains each Android permission in plain language, including the accessibility services. It works as the "prominent disclosure" reference for the Play accessibility declaration too.
- A clear "independent project, not affiliated with MotoEye or Vidoar" statement in the hero and footer.

## Recommendations (not yet done)

### High

1. **Register in Google Search Console** (domain property `a42r.com` or URL prefix `https://motoeye-remote.a42r.com/`) and submit `sitemap.xml`. Do the same in Bing Webmaster Tools, which can import from Search Console. The niche is tiny, so most early traffic will come from the first indexing.
2. **Link from www.a42r.com.** The portfolio lists MotoEye Remote as "Coming soon" with no link. Change it to Live and link `https://motoeye-remote.a42r.com/` (`site/projects.yaml`). It's the only inbound link the site will have at launch.
3. **Link from the GitHub repo.** Set the repo "Website" field to the site, and add the site link to the top of the README. GitHub ranks well for "MotoEye remote"-type queries and will pass visitors along.
4. **Name and trademark.** "MotoEye" is a third-party brand. The site makes the independence clear, but:
   - **Google Play** may treat an app *titled* "MotoEye Remote" as implying affiliation (impersonation / intellectual property policy). A listing title such as **"Remote for MotoEye E6 HUD"** describes compatibility rather than identity, and it also matches what people search for.
   - **Search:** the official MotoEye site and app will outrank this site for brand-only queries. Aim for the compatibility queries ("MotoEye E6 remote app", "MotoEye HUD phone control", "MotoEye TPMS app") — the page titles already do.

### Medium

5. **Add the Google Play link once live.** Replace the "Google Play — coming soon" button with the Play badge and URL, add the Play URL to `sameAs`/`downloadUrl` in the `MobileApplication` JSON-LD, and set `"installUrl"`. A Play listing also gives the site a strong, relevant inbound link (the listing's website field).
6. **Rider communities.** Post the site (not just the APK) in MotoEye owner groups, the EUC rider communities that already use the HUD, and r/motorcycles-adjacent gear threads. Those links are worth more than any on-page change in a niche this small.
7. **A short demo video** (YouTube) of the remote driving the HUD, embedded on the home page with `VideoObject` markup. Video results show up for product queries like this, and the video can double as the Play accessibility and foreground-service declaration video.
8. **Stylesheet cache-busting.** CSS has a 7-day expiry with no content hash, so returning visitors can see old styles for up to a week after a CSS change. Add `styles.css?v=<hash>` (like the www.a42r.com site) and raise the expiry to 30 days.

### Low

9. `og:locale` is `en_AU`. Most HUD buyers aren't in Australia, but the content is region-neutral, so leave it unless you translate.
10. Optional Cloudflare purge in the deploy workflow: add `CLOUDFLARE_ZONE_ID` and `CLOUDFLARE_API_TOKEN` (Cache Purge) secrets, otherwise the edge may serve cached assets until they expire.
11. Trim `icon-512.png` (84 KB) with `oxipng`/`pngquant`. It's only used by the manifest, so this is cosmetic.

## Hosting record

- DNS: `motoeye-remote.a42r.com` existing proxied record → Oracle VM `161.33.93.37`
- nginx: `/etc/nginx/sites-available/motoeye-remote.a42r.com` (symlinked into `sites-enabled`)
- Clean URLs: `/privacy` and `/setup` serve the `.html` files; `*.html`, `/index.html`, `/privacy/` and `/privacy-policy` 301 to the clean URL. `location = /` serves `index.html` directly, because an internal index redirect would loop on the `/index.html` → `/` rule. Cloudflare cached that loop's 301 for a few minutes when it briefly existed, so purge or wait after a redirect mistake.
- Play privacy policy URL: https://motoeye-remote.a42r.com/privacy
- TLS: `/etc/letsencrypt/live/motoeye-remote.a42r.com/` (DNS-01 via `/root/.secrets/cloudflare.ini`, auto-renewing)
- Content root: `/opt/apps/motoeye-remote/website` (owner `ubuntu`)
- CI: `deploy-website.yml` on push to `master` touching `website/**`. It needs `DEPLOY_*` secrets and an rrsync-restricted key in `~ubuntu/.ssh/authorized_keys`.
- Manual deploy: `rsync -rlvz --delete website/ oracle-cloud:/opt/apps/motoeye-remote/website/`
