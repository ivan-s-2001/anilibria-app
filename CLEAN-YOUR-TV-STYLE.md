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

## Catalog follow-up
- Five equal-width filter capsules stay inside the title area's available width.
  Long year/season/genre selections ellipsize instead of pushing status off screen.
- Year, season and genre labels identify their purpose even before a selection.
  Status distinguishes all releases from completed releases.
- Filter buttons share the home navigation's high-contrast focus treatment,
  with 44 dp minimum height and single-line labels. Existing D-pad links and dialogs remain.
- Verify long multi-select values and all five filters with D-pad at 720p/1080p.
  Compilation was attempted again; Gradle distribution download fails with network unreachable.

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
Shared moving spring highlight, precise launcher ambient glow, explicit restoration
of a stable card after process death, and wider catalog/search/player redesign.
Outfit assets remain bundled with their OFL license, but are not selected by the TV theme
until a Cyrillic-complete companion font is chosen.
