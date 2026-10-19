# Third-party notices

The SDK's newly authored source is supplied under the MIT license in LICENSE.

The Gradle wrapper JAR and launcher scripts are from the Gradle 8.13 release,
copyright their original authors, under Apache License 2.0. They retain their
upstream notices. See gradle/wrapper/LICENSE for the license text and
https://github.com/gradle/gradle/tree/v8.13.0 for upstream sources.

Build-time and runtime dependencies are resolved through Gradle or the optional
verification tool; their binaries are not bundled in the source archive:

- Kotlin and kotlinx.coroutines: JetBrains / Kotlin contributors, Apache 2.0.
- Android Gradle Plugin and Android tooling: their upstream license terms.
- JUnit 5 / Platform: Eclipse Public License 2.0.
- Robolectric android-all: Android/Robolectric upstream licenses. Used only as
  the API compilation classpath of tools/verify.py, not shipped in the SDK.
- JetBrains annotations and compiler transitive dependencies retain their
  respective upstream license terms.

Consult upstream artifacts and their embedded license files when redistributing
any dependencies or binary distributions.
