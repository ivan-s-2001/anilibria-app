# AniLiberty TV — CleanYourTV styling
Base: anilibria/anilibria-app a188bfd5e49fd5afee5ff3ec253db92176eaf532.
Scope: TV UI only. Existing release posters remain portrait (130 x 185 dp).
Existing video thumbnails retain their native wide proportions.
Catalog, navigation destinations, accounts, API, playback, history and data are unchanged.

Included: black background, neutral dark surfaces, Outfit with Android system
fallback for Cyrillic, 16 dp poster corners, glass sheen and rim on focused
posters, 21 dp capsule actions and header buttons, matching light/dark focus text.
Outfit license is in app-tv/FONT-LICENSE-OFL.txt.

Leanback retains its existing focus navigation and zoom. The launcher's moving
shared spring highlight and ambient backdrop are not yet ported.
No APK or runtime visual verification: this environment cannot download Gradle
9.1.0 and has no configured Android SDK. All 59 TV XML resources parsed and
git diff --check passed. These checks do not replace an Android build.

Build on a configured Android development machine (JDK 17, SDK 36):
Windows: gradlew.bat :app-tv:assembleAppDebug
Linux/macOS: ./gradlew :app-tv:assembleAppDebug
Output: app-tv/build/outputs/apk/app/debug/app-tv-app-debug.apk
A debug APK cannot update the official APK signed with a different key.
