---
title: Make your first change
description: Clone Hephaestus, run it locally, change one web app component, prove the change with its test, and open a pull request.
page_type: tutorial
---

At the end of this tutorial, you have a pull request with one small change to the web app.
The pull request has a release note and the checks that prove the change.
The tutorial changes a component of the web app, because its test runs in seconds and needs no database.
The linked pages own the details of each stage.

## Before you start

- A GitHub account. If you are not a maintainer, fork the repository.
- The tools of [Local development § Prerequisites](./local-development#prerequisites).
- A GitHub OAuth app for local sign-in, as [Configure GitHub login](./local-development#configure-github-login) describes.

## 1. Get the code

1. Clone the repository, or your fork:

   ```bash
   git clone https://github.com/hephaestus-build/Hephaestus.git
   ```

2. Go to the clone:

   ```bash
   cd Hephaestus
   ```

3. Install the dependencies:

   ```bash
   vp install
   ```

`vp --version` prints the `vite-plus` version that the repository pins.

## 2. Start Hephaestus

1. Set the client ID and the client secret of your OAuth app, as [Configure GitHub login](./local-development#configure-github-login) describes.
2. Start the stack:

   ```bash
   vp run dev
   ```

3. Open the web app on the port that the [port map](./local-development#default-port-map) gives.
4. Sign in with GitHub.

You see the web app with your GitHub account.

## 3. Change one component

1. Create a branch:

   ```bash
   git switch -c <branch>
   ```

   Replace `<branch>` with a short name for the change.

2. Find a component under `webapp/src/components/` that has a test file, such as `PasskeySection.test.tsx`.
3. Change one text or one behavior of the component.
4. Change its test so that the test asserts the new behavior.

The web app in the browser shows the change.

## 4. Prove the change

1. Run the test file of the component:

   ```bash
   cd webapp
   vp test --run src/components/<area>/<Component>.test.tsx
   ```

   Replace the path with the path of the test file that you changed.

2. Go back to the repository root:

   ```bash
   cd ..
   ```

3. Run the checks of the changed trees:

   ```bash
   vp run check:affected
   ```

4. Write a release note, as [`.changeset/README.md`](https://github.com/hephaestus-build/Hephaestus/blob/main/.changeset/README.md) describes:

   ```bash
   vp exec changeset
   ```

5. Format the change:

   ```bash
   vp run format
   ```

The test file and `check:affected` pass.

## 5. Open the pull request

1. Commit the change with a [Conventional Commit](https://github.com/hephaestus-build/Hephaestus/blob/main/CONTRIBUTING.md#pull-request-title-guidelines) title.
2. Push the branch.
   The pre-push hook runs `check:push`.
3. Open a pull request.
   Fill in the [pull request template](https://github.com/hephaestus-build/Hephaestus/blob/main/.github/PULL_REQUEST_TEMPLATE.md).
4. Under **Visual evidence**, add before and after images of the changed screen.

CI runs on the pull request, and a maintainer reviews it.

## Next steps

- [Local verification](./local-verification): what each check proves, and when to run the complete `vp run check`.
- [Testing](./testing): the test tiers and where a test belongs.
- [Coding guidelines](./coding-guidelines): the conventions that a review checks.
- [Local development](./local-development): the rest of the stack, such as a local AI model and the worker.
