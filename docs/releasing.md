# Releasing (for the maintainer)

## Each release
1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName`. The helper checks the version code, so a running helper from the old version is replaced after the update.
2. Rewrite the "What's new" text: `whats_new_body` in `app/src/main/res/values/strings.xml`. Add the same notes to `CHANGELOG.md`.
3. Build: `.\gradlew.bat lintRelease assembleRelease`. It must end with `BUILD SUCCESSFUL` and lint must report "No issues found".
4. Test on a phone with a Move: plug in, test tone, Stop, both modes.
5. Copy `app/build/outputs/apk/release/app-release.apk` to `MoveBridge-<versionName>.apk`.
6. On GitHub: Releases → Draft a new release → tag `v<versionName>` → paste the changelog entry → attach the APK → Publish.

The APK is **not** committed to the repository (`*.apk` is git-ignored). It only lives on the Releases page.

## The signing key
Release builds are signed with the key named in `keystore.properties` (git-ignored). The key lives outside the repository. **Keep a backup.** If it is lost, nobody can update an installed copy: every user would have to uninstall first. Never commit the key or `keystore.properties`.

## The project's address in the app
`REPO_URL` in `app/build.gradle.kts` is the project's GitHub address (`https://github.com/rene415/move-bridge`). The feedback screen's "Open a GitHub issue" button uses it; if it is empty, the button is hidden.

## Things to keep in mind
- The public history starts at the first public commit. The development history before it contains files that were later removed from the public tree; it is not part of this repository and must never be pushed.
- The name and email git puts on commits are public (`git config user.name`, `git config user.email`). This repository uses GitHub's private "noreply" address.
- Only push the `main` branch (`git push origin main`), never `--all` or `--mirror`.
- If a library is added or updated, bring `THIRD_PARTY_NOTICES.md`, `NOTICE` and `app/src/main/res/raw/notices.txt` up to date.
