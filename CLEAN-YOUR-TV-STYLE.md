# AniLiberty TV — CleanYourTV

TV interface changes on top of upstream a188bfd5e49fd5afee5ff3ec253db92176eaf532.

## Current implementation
- Top navigation: Home, My, Catalog, Search, Profile. Existing destinations and account actions remain accessible.
- Home includes the existing Continue Watching provider before favorites and the feed.
  The row appears only when stored history matches episode-access data.
- Portrait release posters keep 130 x 185 dp image dimensions; titles are always visible.
  Existing YouTube thumbnails retain wide proportions.
- Detail page: poster on the left, information on the right, compact capsule actions.
  Continue shows its episode number; the other playback action becomes Episodes after viewing.
  Full description opens in a scrollable native dialog.
- Detail data updates no longer unconditionally steal focus from the active control.
- The selected home page is saved through fragment recreation.
- Black background, neutral surfaces, rounded posters and glass focus treatment.
  A system sans-serif family covers Latin and Cyrillic consistently.
- Existing API, playback engine, authentication rules and local storage remain unchanged.
  No new dependencies.

UI/UX Pro Max informed visible focus and state consistency. Ponytail informed reuse
of Android/Leanback and existing data providers. No new recommendation engine or
account requirement. No launcher integration in this change.

## Viewing UX and layout revision
- Reference composition: Lampa `full/start_new` (poster, metadata and primary actions),
  permanent captions, separate catalog sorting/filter entry points. Zona Android's official
  TV preview informs the compact poster grid; its application code was not copied.
- Home prioritizes Continue, current releases and schedule. Menu order is Home, Catalog,
  Search, My, Profile. Up returns to the menu only above the first content row.
- Guest launch goes directly to content. Account sign-in remains available in Profile
  and when an authenticated favorite action is requested.
- Detail header wraps content instead of forcing a full screen. Primary Watch/Continue
  and the separate Episodes action sit beside the poster, before the compact description.
  Actions scroll horizontally if long labels exceed available width. Missing description
  and secondary title are hidden. Selecting Episodes always opens episode selection.
- Continue cards attempt direct playback of the most recent available saved episode;
  missing access data or loading errors fall back to details. Legacy completion semantics
  remain unchanged; this does not yet implement completed/next-episode resolution.
- Catalog uses five columns, Sort and Filters buttons, a summary and a filter picker
  with reset. Individual choices reuse existing guided dialogs and apply immediately;
  a cross-category draft/apply workflow is not implemented.
- Catalog refresh cancels an in-flight request when filters change; its progress state
  resets on failure as well as success. No request parameter or endpoint changes.
- One decorative poster focus overlay replaces per-card borders. It tracks visible artwork
  during zoom/scroll with a damped spring; it is not focusable and honors animations off.
  Unlike CleanYourTV, this initial overlay does not cache a per-tile glass texture and
  needs on-device frame-time validation. It does not redraw continually while settled.
- Profile has a compact account panel and an explanation of local history versus favorites.
- CI assembles a debug TV APK on branch pushes/PR changes. No release signing or deployment.

## API reference
The user supplied https://anilibria.top/api/docs/v1; its OpenAPI document is at
https://anilibria.top/storage/api/docs/v1 and describes the separate `/api/v1` contract.
The current fork's query-based adapters are retained. No migration, live credentials,
new content sources or server changes are included in this layout revision.

## Validation
TV XML resources parse, all original detail-view binding IDs are preserved,
layout attributes are checked against accidental text nodes, and git diff --check passes.
The earlier styling version was successfully built on the user's Windows machine.
This new revision is not yet compiled or visually verified: this environment cannot
download Gradle 9.1.0 and has no configured Android SDK.

Build with JDK 17 and SDK 36:
Windows: gradlew.bat :app-tv:assembleAppDebug
Linux/macOS: ./gradlew :app-tv:assembleAppDebug
APK: app-tv/build/outputs/apk/app/debug/app-tv-app-debug.apk

On-TV acceptance:
1. Home opens without a side-menu overlay; every top tab is reachable using D-pad.
2. Catalog, Search, My and Profile open; existing settings remain accessible.
3. Continue is absent for empty history and appears after starting a release.
4. Every poster opens details; captions remain legible and focus stays visible.
5. Continue, episode selection, favorite toggle, More and full description work.
6. Back from dialogs/player/details returns correctly; data refresh does not steal focus.
7. Check long Russian titles, 720p/1080p layouts and rapid repeated D-pad input.

## Still pending
Precise launcher ambient glow, explicit restoration of a stable card after process death,
completed/next-episode resolution and the full player/panel redesign.
Outfit assets remain bundled with their OFL license, but are not selected by the TV theme
until a Cyrillic-complete companion font is chosen.

## Revision acceptance on the TV
1. Guest launch, Profile sign-in/out and authenticated favorites.
2. First and lower content rows: repeated Up/Down and rapid Left/Right without lost focus.
3. Poster frame follows zoom and scroll, hides on buttons/player, settles without idle animation.
4. Long title and all detail actions at 720p; Watch starts playback, Episodes opens selection.
5. Catalog filters/reset, fast changes on slow network and failure/retry; five-column grid.
6. Back from playback and dialogs; retained history; single-episode and unavailable releases.
7. Compare PSS and frame time on realme Android 11 and Xiaomi Android 14.

## Compact layout and restrained motion
Posters are now 112 x 160 dp with six catalog columns. Detail art is 128 x 182 dp,
with a 26 sp title and a two-line description. Top tabs are 38 dp tall.
Rows no longer insert a description block only on selection; metadata belongs to each
card's persistent info area. Leanback poster zoom is disabled for rows and catalog.
The shared frame uses a short non-oscillating approach instead of a spring; button
focus scale is reduced from 1.10 to 1.02. Background fades take 240 ms.
CI setup explicitly requests platform-tools instead of the unavailable legacy tools package.
Visual density and frame timing still require real-TV validation.

## Screenshot-led cinema composition
Based on the user's 1920 x 1080 detail screenshot: the earlier 128 dp poster and
full-width metadata left an unbalanced information slab. The detail poster is now
216 x 308 dp, with a 32 dp gutter and a 32 sp heading. Metadata, Full HD and the
favorite count form one group beside it. Description is a bounded text block with
transparent resting background; actions follow it and Watch has a distinct play icon
and stronger outline. The redundant down arrow is hidden. Background palette colors
are blended 65% toward black to avoid a flat colored wash.

Browse posters are 150 x 214 dp and the catalog has five columns. Existing data,
playback routes and reduced motion behavior are preserved. Recommendations are
accurately labeled Similar by genre.

Reference code inspected (composition only, no source copied):
- yumata/lampa-source: src/templates/full/start_new.js and corresponding SCSS.
- jellyfin/jellyfin-androidtv: app/src/main/res/layout/view_row_details.xml.
- xbmc/xbmc: addons/skin.estuary/xml/DialogVideoInfo.xml.

Validate the first detail viewport with one/two-line Russian titles, missing description,
long metadata and unavailable playback; verify the recommendation row after scrolling.


## Web-style poster preview

Focused release posters show a delayed information overlay after 420ms: title, alternate title, available metadata, genres, status and a bounded synopsis. Data comes from the existing Release response. The overlay reuses the loaded poster, uses the official web palette (#121212 / #212121 / #FE3635), does not take focus, and does not resize rows. OK still opens the release. Long text is truncated; full content remains in details. YouTube and utility cards keep their existing behavior.

Navigation now has a direct Schedule destination. The selected page uses the red web accent, while focus has its own high-contrast state. Welcome text sizes share theme resources.

Windows update: after pulling, run `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Update-TV.ps1`. The script stops on update/build/install failure and never uninstalls the existing app. Hardware validation of the preview placement, readability and DPAD navigation is still required.

Saved official web assets confirm `brightness(5%) contrast(90%)` for poster overlays, 12px card corners, Inter body text and Montserrat headings (`entry.BnrmNrTc.css`). The native preview applies the CSS filter as a color matrix without modifying the shared poster drawable. The supplied archive does not contain font binaries or the lazy-loaded hover component implementation; native system fonts remain a fallback for now.

## Homepage composition from the supplied saved page

The main screen now has a wide featured release above the existing sections, a branded navigation bar, larger 188x270dp home posters, 16dp card gaps, and a fixed neutral background. Focus information stays inside the poster instead of expanding over neighbors. The primary feed is labelled New episodes. Existing continue, schedule, favorites and team-video sources remain in use. The featured release comes from the loaded feed; this is not the official website promotions feed. Support, franchise, genre and torrent homepage modules are not implemented by this change. Existing server contracts are unchanged.
