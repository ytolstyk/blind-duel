---
name: bump
description: Bump the app version (versionName + versionCode in app/build.gradle.kts), roll the CHANGELOG.md [Unreleased] section into a dated release entry, and commit both with the version in the message. Use when the user types /bump, optionally with major, minor, patch, or an explicit version like 1.3.
disable-model-invocation: true
argument-hint: "[major|minor|patch|<x.y[.z]>]"
---

# /bump

Bump BlindDuel's version, cut a changelog entry, and commit. Argument: `$ARGUMENTS`.

## 1. Read current state

- `app/build.gradle.kts` → `versionCode` and `versionName` (in `defaultConfig`).
- `CHANGELOG.md` → the `## [Unreleased]` section.

If `[Unreleased]` has no bullet entries under it, stop and tell the user there's nothing to release (don't bump).

## 2. Compute the new version

`versionName` is `MAJOR.MINOR` (optionally `.PATCH`). Based on the argument:

| Argument | Result (from `1.1`) |
|----------|---------------------|
| *(none)* or `minor` | `1.2` |
| `major` | `2.0` |
| `patch` | `1.1.1` (or `1.1.1` → `1.1.2`) |
| explicit `x.y[.z]` | used as given; must be greater than the current version, otherwise stop and ask |

`versionCode` is always `current + 1`, regardless of argument.

## 3. Apply

1. Edit `app/build.gradle.kts`: update `versionCode` and `versionName` only.
2. Edit `CHANGELOG.md`: keep a fresh, empty `## [Unreleased]` heading at the top, and insert directly beneath it a new heading for the release, with the previous Unreleased content moved under it:

   ```
   ## [Unreleased]

   ## [1.2] - YYYY-MM-DD
   ### Added
   ...existing entries, unchanged...
   ```

   Use today's date (ISO format). Don't rewrite, reorder, or reword the entries being moved. If the file already has earlier dated release sections, leave them untouched.

## 4. Commit

Stage only `app/build.gradle.kts` and `CHANGELOG.md` (by explicit path — never `git add -A`, and leave any other uncommitted changes alone) and commit with the message:

```
Bump version to <versionName> (versionCode <versionCode>)
```

followed by the attribution line from the session's system-reminder, if one is present. Do not push, tag, or build.

## 5. Report

Show the old → new `versionName` / `versionCode`, the changelog heading added, and the commit hash. Mention that `scripts/release.sh` packages the release.
