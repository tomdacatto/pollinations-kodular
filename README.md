# Pollinations for Kodular

The Pollinations extension (`ai.pollinations.ai2`, component `Pollinations`) built for Kodular Creator.
It has the same source, build file and dependency pin as the App Inventor build in
`pollinations-app-inventor`. For the feature list, blocks, sign-in flow and limits, see that
repository's README.

Status: **not verified in Kodular Creator.** The build and tests run here. Kodular itself was not
available, so the extension has not been loaded in it.

## Install in Kodular Creator

1. Download `release/ai.pollinations.ai2.aix`.
2. In Kodular Creator open the extension import (**Extensions → Import**, or the extension
   manager in your Kodular version) and choose the `.aix` file.
3. Add the `Pollinations` component to a screen. It is non-visible.
4. Set **ApiKey** (from https://enter.pollinations.ai/keys), or use **StartSignIn** so users sign in.
   A key set in the designer is compiled into the APK and is visible to anyone who has it.

The blocks are the same as the App Inventor build (`AskText`, `Chat`, `AskAboutImage`, `GenerateImage`,
`Speak`, `Transcribe`, `LoadModels`, `StartSignIn`, `SignOut`, `ErrorOccurred`, and their events).

## Differences from the App Inventor build

- **Code:** none. `src/` and `test/` are byte-identical to `pollinations-app-inventor`.
- **Build:** none. `build.xml` is identical and both use the same pinned `lib/appinventor`
  (`mit-cml/extension-deps` at `78dc0a61`, App Inventor v194 jars).
- **Output:** the `.aix` contents are identical to the App Inventor build. `classes.dex` has the same
  SHA-256, and `AndroidRuntime.jar`'s class files match. Only the `dateBuilt` field in
  `component.json`/`components.json` and the zip timestamps differ.
- **Sample:** there is no `.aia` here. The Kodular project format was not checked against the
  App Inventor format, so `sample/README.md` gives the steps instead.
- **Badges:** no "Compatible with Kodular" badge is claimed. That needs a Kodular test first.

## Kodular rules checked

From the Kodular community threads on extension compatibility (linked in the quest):

| Rule (source) | This build |
|---|---|
| Kodular has historically used the Support Library, not AndroidX; extensions with AndroidX references can fail (community.kodular.io/t/question-about-androidx-in-extension/87158, seen in a search summary, not fetched in full) | No `androidx/` or `android/support/` references in `classes.jar` or `AndroidRuntime.jar`. Checked by scanning the built classes and dex. |
| Bundled libraries can duplicate classes the host already has, causing dex errors such as Volley's `DexException` (community.kodular.io/t/10268) | `lib/deps` is empty. The only dependency is the platform's `org.json`, `java.net`, and the App Inventor runtime. |
| Newer annotations may be ignored or missing on Kodular (community.kodular.io/t/128064) | Only the basic set: `DesignerComponent`, `SimpleObject(external)`, `UsesPermissions`, `DesignerProperty`, `SimpleProperty`, `SimpleFunction`, `SimpleEvent`, and `PropertyCategory` on designer properties. No `UsesServices`, `UsesActivities`, or `Options`. |
| Icon size can make an extension distribution-specific (badge guidance, community.kodular.io/t/128064) | No custom icon; the default icon is used. |
| Bundled third-party libraries can conflict with classes the host already ships (community.kodular.io/t/10268) | None bundled. |

**Not checked:** whether Kodular's own `AndroidRuntime.jar` and annotation processor accept every
symbol used. Kodular's runtime was not available here. The forum threads are from 2018–2021, and one
newer thread says Kodular's AndroidX status changed. The current status is not confirmed.

## Build and test

```bash
git clone --recurse-submodules <this-repo>.git
ant extensions            # writes out/ai.pollinations.ai2.aix
test/run-tests.sh         # 22 JVM tests; needs JUNIT_JAR, HAMCREST_JAR, JSON_TEST_JAR
```

Requirements: JDK 8+ (tested with JDK 21, `--release 8`), Apache Ant 1.10+, git. `build.xml` uses
`release="8"` instead of the template's Java 7 setting, because JDK 21 cannot compile Java 7.

## Verification

- `ant extensions`: BUILD SUCCESSFUL. The `.aix` is in `release/`.
- JVM tests: 22 pass. A deliberate bug in the device-flow back-off makes one test fail.
- Live API checks use the same code as the App Inventor build (see that README).
- Not verified: loading in Kodular Creator, running on a device, and any Kodular-specific behaviour.

## License

MIT. See `LICENSE`. Third-party parts are listed in the App Inventor README.
