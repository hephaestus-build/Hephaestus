#### 🔴 Use the small profile on every single-node install

The redundant `small-16gib` profile is removed. Before upgrading, set `HEPHAESTUS_CHART_PROFILE=small` if it contains `small small-16gib` or `small,small-16gib`. Remove `--values profiles/small-16gib.yaml` from Helm commands and keep `--values profiles/small.yaml`.

The change does not alter the capacity of the `small` profile.

A stale profile setting stops the reconciler with `the release has no chart profile small-16gib` before Helm runs. The previous release keeps serving. The installer selects `small` automatically, so a new install needs no extra step.
