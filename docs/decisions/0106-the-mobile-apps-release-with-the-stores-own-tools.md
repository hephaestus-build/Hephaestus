# ADR 0106: The mobile apps build and code-sign in GitHub Actions with the stores' own tools, and release on a version line of their own

**Status:** Proposed
**Date:** 2026-11-13
**Authors:** Hephaestus maintainers

**Builds on:** [ADR 0104](0104-the-mobile-apps-are-native-clients-of-the-server-api.md), [ADR 0105](0105-one-store-app-signs-in-to-any-instance-by-its-address.md), [ADR 0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md)

## Context

The apps reach people through the App Store and Google Play, under the accounts of the publishing organization.
The repository is public, and CI is GitHub Actions.
One changesets Version PR accumulates every release note, and merging it cuts the server release (`docs/contributor/release-management.mdx`).
Changesets runs in prerelease mode for 1.0 (`.changeset/pre.json`), so a package in that flow gets versions such as `1.0.0-rc.5`.
Apple accepts only three period-separated integers as an app's version.

The forces:

- **A store release cannot be withdrawn from devices.** On Google Play, a staged rollout can halt at a percentage. On the App Store, a phased release limits only automatic updates: anyone can download the update by hand, and a pause lasts at most 30 days ([Apple][apple-phased]). A first release has no phased release. A bad iOS build stops only with a fixed build, through expedited review if needed, or by removing the app from sale.
- **Release credentials are long-lived.** App Store Connect offers no OIDC path, so its API key stays a secret. Under Play App Signing, Google keeps the app signing key, and the repository keeps only an upload key.
- **macOS runners.** Standard GitHub-hosted runners are free for public repositories. The Free plan runs 5 macOS jobs at a time ([GitHub][gh-limits]). Apple requires Xcode 26 for uploads, and Google Play requires target API 36.
- **App and server release on different schedules.** A store review for every server patch would block server releases and gain nothing. [ADR 0105](0105-one-store-app-signs-in-to-any-instance-by-its-address.md) rule 9 decouples the versions.
- **Store review needs a working back end and account deletion.** Apple asks for demo account information and a back end that is on (guideline 2.1). Both stores need account deletion that starts in the app. Google Play also needs a web link where a person can ask for deletion without the app.

## Decision drivers

- The store promotes the same build that testers used.
- A server release never waits for an app release. An app release waits only for a freeze of `main`.
- Few runtimes and few dependencies ([ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md)). Repository automation is typed TypeScript.
- Release secrets are usable only from one protected job.

## Considered options

1. **fastlane for both stores.** It is widely used and still released. It adds Ruby and a large dependency tree for two uploads and one signing step per platform. Its `match` keeps certificates in a git repository behind a passphrase, which one CI identity with an App Store Connect API key does not need.
2. **Gradle Play Publisher for Android, and Apple's tools for iOS.** The plugin fits Gradle, and it puts publishing credentials into the Gradle build. Its README says that issues are ignored and pull requests are not, so fixes would depend on our own pull requests.
3. **Xcode Cloud for iOS.** Apple-hosted, with 25 compute hours a month included. It is a second CI system, and it builds only Apple platforms.
4. **The stores' own tools, called from GitHub Actions (chosen).** iOS: `xcodebuild` archives and signs, and the Transporter command-line tool uploads the build with the same JSON Web Tokens as the App Store Connect API ([Apple][apple-upload]). Android: Gradle builds and signs the bundle with the upload key, and a short TypeScript script uploads it through Google's official Play Developer API client.

## Decision

Option 4.

1. **Pull request CI.** A change under `ios/`, `android/`, `push-relay/` or `server/openapi.yaml` runs the [ADR 0104](0104-the-mobile-apps-are-native-clients-of-the-server-api.md) jobs. The iOS app job runs on a GitHub-hosted macOS runner with the Xcode version that the App Store requires. Path filters keep it off other pull requests.
2. **One plain version line for both apps, outside the server's release flow.** The apps have their own `X.Y.Z` version, never a prerelease. iOS and Android ship the same version from the same commit. A change to the apps or to `push-relay/` records its release note under `mobile/changes/`, not under `.changeset/`, so the server's release planning, Version PR and prerelease mode never see it. A small script keeps a mobile release pull request: it collects those notes, raises the version, and writes the store release notes. That pull request follows the merge rules of `main`, including a freeze.
3. **Merging the mobile release pull request releases the apps.** The release workflow tests that commit, then archives, signs and uploads each app once. iOS signs the app and its notification extension with the distribution certificate and provisioning profiles from the protected environment. Cloud-managed signing would need an Admin key, which can also release to production. The build number is the workflow run number plus a fixed offset. Each store step skips a build that the store already holds, so a rerun after a partial failure finishes only the missing upload. The workflow creates the `mobile-v<version>` tag after both uploads succeed. A GitHub artifact attestation records what CI uploaded. The store processes or re-signs the file, so the attestation does not cover what a device installs.
   The same workflow releases the push relay image ([ADR 0108](0108-the-store-apps-receive-push-through-one-stateless-relay.md)) on this version line. CI builds it once with the SBOM, provenance and vulnerability policy of ADR 0092, and the mobile release workflow signs it with its keyless identity. The admin guide names that identity, so that the publishing organization and a self-hoster verify the image before they deploy it. It is not in the instance lock, because no instance runs it.
4. **Promotion happens in the store, on the same build.** The iOS upload is eligible for the App Store, and its first group is internal TestFlight testers. It is never an upload restricted to internal testing. The Android upload goes to the internal track. A maintainer promotes the build to external testers and closed testing, then to production. Production uses a staged rollout on Play, and a phased release for each App Store update. The release checklist names the stop action for each store: halt the staged rollout, or pause the phased release and ship a fixed build. The CI credentials cannot release to production.
5. **Secrets live in one protected environment.** The `mobile-release` environment accepts only the release workflow on `main` and needs a maintainer's approval. It holds the iOS distribution certificate with its private key, the provisioning profiles of both iOS targets, an App Store Connect API key with a role that can upload but cannot submit for release, the Android upload key, and a Google service-account key that can release only to test tracks.
6. **Store review gets a demo instance, and account deletion has two paths.** The publishing organization runs one instance with a seeded workspace and a review account that store reviewers can use. The review notes of both stores give its address and the account. The app's settings open the deletion section of the instance's `/settings` page, because the account belongs to the instance, not to the app. A page of the publishing organization explains how to delete an account on any instance, and it is the web link that Google Play requires.
7. **The Android build stays deterministic**, so that F-Droid can verify it later. This ADR claims nothing about reproducible iOS builds.

## Consequences

- Good: no Ruby and no third-party release tool. Each platform uses the build tool and the upload path that its store documents.
- Good: a server release never waits for store review or for an app release.
- Good: testers and production get the same build, and a failed release can resume.
- Bad: long-lived credentials: the App Store Connect key, the distribution certificate and its profiles, which a maintainer renews each year, and the Android upload key. A leaked App Store Connect key can upload builds until someone revokes it. The approval gate limits who can use it.
- Bad: a defective iOS release reaches everyone who updates by hand or installs the app, until a fixed build passes review.
- Bad: we own the uploader script, the mobile release pull request script and the release workflow, and we fix them when a store API changes.
- Bad: the demo instance is one more service to run, and a broken one blocks store review.
- Bad: a second changelog and a second release pull request, beside the server's. A freeze of `main` delays an app release.

## Confirmation

- `node scripts/check-workflows.ts` fails when a job that uses the `mobile-release` environment runs outside the release workflow on `main`.
- `vp run gate:changesets` fails when a pull request changes shipped code under `ios/` or `android/` or `push-relay/` without a note under `mobile/changes/`. The server's release planning reads only `.changeset/`.
- The release workflow archives and exports both iOS targets on a fresh runner from the protected secrets, so a missing certificate or profile fails it.
- The release workflow fails when the mobile version is not three integers, when the iOS and Android versions differ, or when the build number does not rise.
- The store accounts enforce the production limit of rules 4 and 5: the CI credentials have no production permission.
- Rule 6 is not enforced. The release checklist catches a stale demo instance.

## Revisit trigger

- macOS jobs queue behind the shared limit and delay pull requests. Then price self-hosted macOS runners or Xcode Cloud.
- A store API changes in a way that the owned uploader cannot follow in one pull request. Then price fastlane again.
- App Store Connect offers short-lived credentials, or Google Play publishing accepts Workload Identity Federation from GitHub. Then remove the long-lived key.
- People ask for F-Droid, or for a build without Google services.

## Sources

- Apple, [Upload builds][apple-upload]: the Transporter command-line tool uploads a build with the same JSON Web Tokens as the App Store Connect API. This makes option 4 possible without fastlane.
- Apple, [Release a version update in phases][apple-phased]: a phased release limits only automatic updates, anyone can download the update by hand, and a pause lasts at most 30 days. This sets the stop actions of rule 4.
- GitHub Docs, [Actions limits][gh-limits]: 5 concurrent macOS jobs on the Free plan. This sets the path filters of rule 1.

[apple-phased]: https://developer.apple.com/help/app-store-connect/update-your-app/release-a-version-update-in-phases
[gh-limits]: https://docs.github.com/en/actions/reference/limits
[apple-upload]: https://developer.apple.com/help/app-store-connect/reference/upload-tools
