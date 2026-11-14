---
title: Scans
description: Scan the files of a repository's default branch against your practices on a schedule, read the report, and export it as SARIF.
page_type: feature
---

A scan reviews every file of a repository's default branch against the practices that support scans.
It sends no feedback to anyone and judges no developer.
Workspace admins and owners set up scans in **Review settings** and read them on **Scans**, under **Administration → Practices**.

GitHub and GitLab repositories support scans. Slack and Outline do not.

## How scans work

A recurring check starts the scans.
It scans the repositories in the workspace's review scope, one at a time, and shares one budget across them.
Each scan reads the files at one commit of the default branch, so the branch cannot move during the scan.

A catalog practice declares whether it supports scans.
A scan observation is about a file, not about a developer.
Binary files and files larger than 2 MiB are not eligible, and no practice reviews them.

The web app has no button to start a single scan.

## Schedule scans

1. Under **Administration → Practices**, open **Review settings**.
2. Under **Add a recurring check**, select **Repositories** in **Kind of work**.
3. In **How often**, choose **Every day** or **Every week**.
4. Enter **Budget for all repositories (USD)**.
5. Select **Start checking repositories**.

The first check happens within the hour.
To stop checking for a while, select **Pause** on the schedule, and **Resume** to continue.
To delete the schedule and its settings, select **Delete**, then **Delete recurring check**.

When a run spends its budget, **Run limit reached** appears beside the schedule.
That run ends, and the repositories that it did not reach are not scanned in that run.
The next check starts a new run from the first repository.
If later repositories stay unreached, raise the budget.

A run also pauses when the workspace's monthly AI budget is used up, or when no review model can run.
It continues from where it stopped when the budget resets, the cap is raised, or a review model is ready.
If the month's spend shows **Unavailable cost**, raising the cap does not resume the run, as [Set a cap on your own provider](./ai-models.md#set-a-cap-on-your-own-provider) describes.

## Read a scan report

1. Under **Administration → Practices**, open **Scans**.
2. Select a scan.

**Reviewed or reused** counts the practice and file pairs that the scan judged.
A scan reuses an earlier observation when the file, its context, the practice, and the model are unchanged.

The report separates these results:

- **Reviewed**: judged in this scan.
- **Reused**: taken from an earlier review.
- **Withheld**: refused with a reason, listed under **Why pairs were withheld**.
- **Not reviewed**: not reached before the scan ended, listed under **Why pairs were not reviewed**.

**Files not eligible for review** lists binary and large files.
They do not keep a scan from being complete.

A scan is **Complete** when it listed all files and judged or reused every pair.
Withheld or unreached work makes it **Partial**.
A **Failed** or **Cancelled** scan keeps the work that it reached.
A scan without problems shows only that the work it reached had none, not that the code is clean.

### Compare with the earlier scan

Each problem shows how it compares with the previous scan of the same repository and branch: **New**, **Unchanged**, **Updated**, or **Absent**.
A comparison needs the same practice standard and the same model.
After a change to either, the practice shows **Standard changed** or **Method changed**.

A problem is **Absent** only when the new scan read the changed file completely.
A withheld pair, a mark, or a removed file does not prove that a problem went away.
A renamed file counts as a new file.

## Cancel a scan

1. Open the running scan.
2. Select **Cancel scan**.
3. Select **Cancel scan** in the confirmation.

The scan stops and cannot resume.
Its report keeps the observations that it already recorded.

## Export SARIF

1. Open a scan that has ended.
2. Select **Export SARIF**.

Each visible problem becomes one SARIF result.
A running scan cannot export.
The export of a partial scan says that the scan did not complete.
After the feedback retention period, the scan's details expire, and the report shows **Details no longer kept**.
Its totals stay readable, but SARIF export is no longer available.
