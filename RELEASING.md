# Releasing the SDKs

One tag releases everything: the Java SDK and Spring Boot starter (with the tag as their version) and the web SDK
(with its own version, attached to the same release). Tags are versions, e.g. `0.3.0`, never `v0.3.0`.

## Before the tag

1. **main is green:** the Java and web workflows passed on the last commit (Java 8, 11, 17, 25; Node 20, 22).
2. **Changelogs:** `java/CHANGELOG.md` and `web/CHANGELOG.md` say what changed, in words a developer using the SDK
   understands. Behaviour changes are marked as such. Replace "not released yet" with the date.
3. **Versions:**
   - **Java:** the docs and examples name the new version. Search for the old one in `README.md`,
     `DEVELOPER-GUIDE.md`, `java/` and `java/examples/`. The poms move to the next `-SNAPSHOT`, because JitPack builds
     the tag with the tag as its version.
   - **Web:** `web/package.json` and `SDK_VERSION` in `web/src/client.ts` are the same. The install lines and the
     script tag name the new release, e.g. `releases/download/0.3.0/observa-web-0.7.0.tgz` and
     `observa-sdks@0.3.0/web/browser/observa.js`.
4. **The script file is current:** `cd web && npm run build` leaves `git status` clean. CI checks it too.
5. **Commit:** "Release 0.3.0: …", through a pull request like any change.

## The tag

```sh
git checkout main && git pull
git tag -a 0.3.0 -m "Release 0.3.0" && git push origin 0.3.0
```

## After the tag

1. **JitPack:** open `https://jitpack.io/#the1zozz/observa-sdks/0.3.0` and press *Get it*. The build log must end in
   success, and `https://jitpack.io/com/github/the1zozz/observa-sdks/observa-java/0.3.0/observa-java-0.3.0.pom` must
   answer 200.
2. **The files:**
   ```sh
   cd java && ./mvnw -B -q versions:set -DnewVersion=0.3.0 -DgenerateBackupPoms=false && ./mvnw -B -q package -DskipTests -Prelease && git checkout -- .
   cd ../web && npm ci && npm pack        # observa-web-0.7.0.tgz
   ```
3. **The GitHub release** for the tag, with the changelog entries as its notes, and these files attached:
   - the Java jars (`observa-java`, its sources, `observa-logback`, `observa-spring-boot-starter`);
   - `observa-web-<version>.tgz` and `web/browser/observa.js`.
4. **Check as a stranger would**, with no token and a fresh cache:
   - **Java:** a fresh Maven cache (`-Dmaven.repo.local=…`) builds `java/examples/quickstart` from JitPack;
   - **npm:** `npm install <release tgz URL>` in an empty folder;
   - **script tag:** the jsDelivr URL of the script answers 200 with `@observa/web <version>` on its first line.
5. **The portal:** the Java snippets next to a new secret key name the new version (`JAVA_SDK_VERSION` in
   `observa-web/src/app/areas/data/sources/key-reveal.ts`, in the platform repository), and so does the web snippet.
