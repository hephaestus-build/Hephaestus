#### 🔴 Pull-based hosts with tooling from before 1.0.0-rc.4 need one manual step

The reconciler verifies a release with the tooling of the release before it. Tooling from before 1.0.0-rc.4 expects five chart images, and 1.0.0-rc.4 has nine, so it stops each run with `does not pin the v1.0.0-rc.4 images`. The running release is not affected. Helm installs that do not use pull-based deployment need no action.

Follow [Hosts with reconciler tooling from before 1.0.0-rc.4](https://docs.hephaestus.build/admin/upgrade#hosts-with-reconciler-tooling-from-before-100-rc4) once, after the host runs 1.0.0-rc.4 or later, and before you resume the timer.
