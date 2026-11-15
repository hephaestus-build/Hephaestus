---
title: Qualify a release
description: Hold staging on one commit, then run the turn smoke, the review smoke, the capacity bench and the failure drills against it.
page_type: how-to
---

Use this procedure before a release goes to production, and after a change to the sandbox runtime, the run claim or the chart's resources.
It measures the release on staging, or on a disposable k3d cluster, and writes one evidence folder per run.
[Qualification checks](./qualification-checks.md) defines each check and its bar.

## Before you start

- A checkout of the commit that staging runs, with `vp install` done.
- For staging: permission to run **Release / Promote**, the GitHub CLI signed in, and a command that runs a shell command on the staging host as a user with `sudo`.
  Set it in `QUAL_STAGING_EXEC`, for example `QUAL_STAGING_EXEC="ssh <host>"`.
  The scripts add the shell command as the last argument.
- For k3d: a cluster that `scripts/kubernetes-cluster.ts` installed, and its kubeconfig file.
  The scripts never use the current context.
- Synthetic workspaces, accounts and repositories only.
  A review or a turn in the window sends feedback to the people of that workspace.

Every run writes its evidence to `.context/qual/<item>-<UTC>/`: `README.md`, `summary.json` and `raw/`.
Use `--out` for another directory.

## 1. Hold staging on one commit

1. Set `QUAL_STAGING_EXEC` to the command that reaches the staging host.
2. Run each staging command through the staging hold:

   ```bash
   node scripts/qual-staging-hold.ts -- node scripts/qual-<entry>.ts --target staging …
   ```

The hold freezes automatic promotion on the commit that staging has applied, with a Staging promotion and `freeze` ([Pull-based deployment](../admin/pull-based-deployment)).
It runs the command, then lifts the freeze with a promotion without `freeze`.
It also lifts the freeze when the command fails or you interrupt it.
`qual-bench.ts`, `qual-drills.ts`, `qual-smoke.ts` and `qual-turn-smoke.ts` refuse `--target staging` without the hold.

The hold exits with the command's exit code, with these exceptions:

| Exit code | Meaning | Action |
|---|---|---|
| 2 | The freeze was not lifted | Run **Release / Promote** for Staging and the applied commit, without `freeze` |
| 3 | Staging applied another commit during the run | Discard the evidence and run again |
| 130 | You interrupted the hold before the command started | None |

## 2. Run the turn smoke

The turn smoke asks one Ask Hephaestus question and passes only when the turn completes in a conversation sandbox.

1. Write a synthetic member's bearer token to a file.
   The script reads it and never prints it.
2. Run the smoke:

   ```bash
   node scripts/qual-staging-hold.ts -- node scripts/qual-turn-smoke.ts --target staging \
     --conversation-url https://<host>/api/workspaces/<slug>/conversation --token-file <file>
   ```

If no token can leave a browser, use `--await-workspace <workspace id>` in place of the URL and the token file.
Ask the question in the signed-in app after the script prints `waiting for a turn`.
The script judges the first turn that the workspace gets after it started.

## 3. Run the review smoke

The review smoke starts reviews without a signed-in session.

1. Write a fixture outside the repository.
   It names a synthetic workspace by ID and slug, and open pull requests of its test repositories:

   ```json
   {
     "workspaceId": 1,
     "workspaceSlug": "qualification",
     "reviews": [{ "repo": "owner/name", "pr": 1, "path": "src/File.java" }]
   }
   ```

2. Run the smoke:

   ```bash
   node scripts/qual-staging-hold.ts -- node scripts/qual-smoke.ts --target staging --fixture <file> --count 1
   ```

The script refuses to start when the slug does not match the ID.
It turns on the workspace's practice-review auto trigger and pushes one comment line to each pull request.
It turns the trigger off again when each review started.
A systemd timer on the host turns the trigger off after 15 minutes if the script stops before that.

## 4. Run the capacity bench

1. Use a fixture with at least 4 pull requests.
2. Run the bench:

   ```bash
   node scripts/qual-staging-hold.ts -- node scripts/qual-bench.ts --target staging \
     --fixture <file> --rounds 3 --round-gap-minutes 45 --minutes 35 --statements <file>
   ```

A round collects for `--minutes`: the Pod cgroups, the node, the Prometheus series of the server, and SQL samples.
`--statements` names the statements that B11 judges.

On k3d, the bench starts no review, because a k3d cluster has no SCM connection.
Use `--target k3d --kubeconfig <file> --cluster <name>`.
Add `--memory-drill --images <release-images.tsv>` to run B8-tier on a disposable node.

## 5. Run the failure drills

1. Start reviews, so that each drill has a run in flight.
2. Run the drills:

   ```bash
   node scripts/qual-staging-hold.ts -- node scripts/qual-drills.ts --target staging --drill all --minutes 10
   ```

D1, D2, D4 and D5 change the cluster: they kill a Pod, delete a claim, scale the agent-sandbox controller to 0, or restart the server.
D4 always scales the controller back.
D3 prints its manual step.

## Check that it worked

Each run prints its verdict and the path of its evidence folder:

```text
verdict PASS; evidence in .context/qual/bench-staging-20261114T181138Z
```

Open its `README.md`.
It names the command, the commit of the scripts, the cluster and its images, and one row per check.
A `not-run` check names what it needs.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `refusing to measure staging without a promotion freeze` | The command did not run through the staging hold | Run it as step 1 shows |
| `QUAL_STAGING_EXEC is not set` | The transport to the staging host is missing | Set `QUAL_STAGING_EXEC` |
| A check is `not-run` with `a cgroup reading of every Pod` | The node script found no cgroup for a Pod | Check that the node runs cgroup v2 |
| The turn smoke fails with `a context read took … s` | A slow context read delays the holder's lease renewals, so the lease runs out | Find the slow statement in `pg_stat_activity` while the turn runs |
