---
title: Correct commit links
description: Let GitHub or GitLab correct the author, committer and contributor links of stored commits, and undo a wrong correction.
page_type: how-to
---

Correct commit links when the author, committer or contributor of a stored commit links to the wrong account, or to no account.
The correction job asks GitHub or GitLab which account owns each commit email of each repository.
It asks about 50 emails every 5 minutes, and stops a run when the rate limit of the provider is near.
GitHub answers several emails in one request. GitLab uses one request for each author email.
GitLab leaves an email that appears only as a committer or a co-author unchanged.

## Before you start

- You have Helm access to the release and your site values.
- You have read access to the application metrics ([Scrape metrics](./monitoring#scrape-metrics)).

## Watch the correction

The correction job runs by default.

1. Read `commit_identity_pending`.
   The queue shrinks as the provider answers.
2. Read `commit_identity_resolutions_total` by `outcome`:
   - `resolved`: the provider named an account, and the links follow it.
   - `no_account`: no account owns the email, so the links are cleared.
   - `erased`: the provider named an erased account, so the links are cleared.
   - `skipped`: another attempt already finished the email. No link changed.
   - `retry` and `unanswered`: no answer came. No link changed. After 5 attempts, the email leaves the queue.

## Check that it worked

1. `commit_identity_pending` falls to 0.
2. Open a commit that had a wrong link.
   Its author links to the account that the provider named.

## Undo

`commit_identity_change` keeps each changed link for 90 days, with `target`, `row_id`, `old_user_id` and `new_user_id`.

1. Find the row of the wrong correction in `commit_identity_change`.
2. Run one conditional `UPDATE` on the table and the column that `target` names.
   Set the column to `old_user_id`.
   Match `id` to `row_id`, and require that the column `IS NOT DISTINCT FROM new_user_id`.
3. If the update changes no row, the link changed again. Leave it.
4. For a restored author link, restore the `COMMIT_CREATED` Activity actors of that commit in the same way.
   Match the repository and the commit ID, and change only actors that are `IS NOT DISTINCT FROM` the `new_user_id`.
   Never restore an erased account.

A committer or contributor change does not change the Activity actor of the commit.
An Activity correction without a changed commit link has no change record, so you cannot undo it.

To stop the job, set `config.commitIdentityResolutionEnabled: false`, and run `helm upgrade`.
To resume it, set the value to `true` again.

## Troubleshooting

### Ask again after a provider outage

**Cause:** The provider did not answer 5 attempts, so the emails left the queue as `UNANSWERED`.
`HephaestusCommitIdentityResolutionUnanswered` fires.

**Fix:**

1. Read the `Commit identity correction failed` log record for the queue ID and the cause.
2. Fix the cause, for example the connection that monitors the repository.
3. On the `UNANSWERED` rows of `commit_identity_resolution`, set `state` to `PENDING` and `attempts` to 0.

**Check:** `commit_identity_pending` rises, then falls as the provider answers.
