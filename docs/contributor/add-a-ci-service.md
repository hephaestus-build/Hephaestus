---
title: Add a service to CI
description: Add a new service, image or test suite to the CI pipeline at the ownership boundary that changed.
page_type: how-to
---

Add a service to CI when a change adds a new deployable part, image or test suite.
Extend the pipeline at the ownership boundary that changed. Do not copy an existing job wholesale.

1. Add the service's paths to `detect-changes` in `cicd.yml`, including the shared files that can
   affect it. Expose the result as a reusable-workflow input.
2. Add purpose-named jobs to `ci-quality-gates.yml`, `ci-tests.yml` or `ci-build.yml`. Declare
   `needs` only when a job consumes another job's artifact.
   Give every runnable job least-privilege permissions and a timeout of [at least 15 minutes](./ci-cd.mdx#parallelism-and-concurrency).
3. Use `setup-toolchain` with an explicit install mode.
   Use `frozen` for any job that calls `vp`, because Vite+ loads the task graph from workspace configs. `none` only for jobs that run
   `node scripts/…` or shell alone. Java jobs use a validated `setup-caches`
   type. Browser jobs use `setup-browsers`.
4. Add a Dockerfile image as a target of `docker/docker-bake.hcl` and an input of
   `reusable-docker-build.yml`. Keep image metadata, immutable tags, attestations, and signing in
   that workflow rather than reproducing them in the caller. In that workflow, also add the image to:
   - `IMAGE_NAMES`, the one list that the image selection and every name check read, and a boolean input of the same name.
   - A metadata step, its `bake-file-labels` entry, and the image list of the merge job's metadata step.
   - A digest output and an `Attest the <image> image` step.
   - `imageAttestations` in `scripts/resolve-server-build.ts`, so that a push can reuse the image.
   - Its `*_changed` input in `ci-docker-build.yml` and `security/release-images.json`.
5. Pass the change-detection output from `cicd.yml` to every reusable workflow that needs it.

## Check that it worked

1. Run `node scripts/check-workflows.ts`. It reports no error.
2. Run `node --test scripts/ci-contract.test.ts`. Every test passes.
   This test owns the workflow invariants. If the service adds an invariant, add a test for it first.
3. Open a pull request that changes the service's paths. The new jobs run, and a pull request that does not change them skips them.
