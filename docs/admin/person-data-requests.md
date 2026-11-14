---
title: Answer an access or erasure request
description: Export or erase what Hephaestus holds about one person with the person-data console, and reply to the request.
page_type: how-to
---

Answer a request from one person to see or erase their data with **Instance administration → Person data**.
The console also finds people who never signed in, such as contributors whose work a workspace collected.
It is not a request portal: requests and replies go through the privacy contact of your instance.
A member can also delete their own account under [Danger zone](/user/user-settings#danger-zone).

## Before you start

- You are an instance admin with a recent sign-in.
  The preview, the export and the erasure require it.
- You verified the identity and the authority of the requester, as your privacy procedure and the [data-protection documentation](./dsms) require.
- You have the exact identities of the person.
  An identity is an account ID, or a provider instance with the native provider user ID.
  For Slack, you also have the native Slack workspace ID.
  Names, logins and email addresses are not proof of an identity.
- For an erasure, you know which feedback copies on providers must go.
  Erasure removes the records that locate them.

## Preview the data

1. Open **Instance administration → Person data**.
2. Under **Find a person**, enter the **Account ID**, or select a **Provider instance** and enter the **Provider user ID**.
3. For a Slack identity, also enter the **Slack workspace ID**.
4. To add another verified identity of the same person, select **Add provider identity**.
5. Select **Preview data**.
6. Check the resolved identities and the row count of each store.

   Export and erasure use exactly the rows of this preview.
   The preview expires after 48 hours.
   If the data changes, preview again.

## Answer an access request

1. Select **Download JSON export**.

   The export has a schema version and a `stores` object, keyed by store name.
   It has no credentials, no session capabilities and no profiles of other people.
   It does not redact names, secrets or details of other people in source text.
   Its `git_repository_history` section lists the repositories whose history can hold commits of the person.

2. Read the shared-source and free-text content of the export for the rights of other people (GDPR Art. 15(4)).
3. If a disclosure would harm another person, redact that part of the delivery copy, and record the risk.
   Do not change the canonical records.
4. Write the reply from your [record of processing](./dsms/record-of-processing.md).
   Include the purposes, categories, recipients, retention, rights, complaint route, sources and transfer safeguards (GDPR Art. 15).
5. Send the export and the reply to the verified requester through a secure channel.
6. Delete your delivery copy when its expiry date comes.

The export alone does not complete the reply.
The [EDPB right-of-access guidelines](https://www.edpb.europa.eu/documents/guideline/guidelines-012022-on-data-subject-rights-right-of-access_en) give the full duty.

## Erase the data

1. Write the account ID and the exact native identities of the person in your restricted incident record.
   Give each identity with its provider type and server URL, and the Slack workspace ID for Slack.
   After a database restore, you need them to erase the person again ([Reapply erasures after a restore](./erasure-recovery)).
   The erasure removes them from Hephaestus.
2. If the preview shows **Feedback is still posted on providers**, remove each listed copy.
   [Remove feedback from a provider](./remove-feedback-from-a-provider) gives the steps.
3. Select **Erase data…**.
4. In the dialog, confirm the removal of the listed provider copies.
5. Enter `ERASE`.
6. Select **Erase data**.

   The page follows the job until it finishes.
   Keep the address of the page to return to the request after a reload.

7. Wait until the page shows **Erasure complete** and the receipt of each store.
8. Tell the requester what stays outside the control of Hephaestus:
   - The original records at the provider, and provider comments that you did not remove.
   - Delivered emails.
   - Backups, until they expire ([Backup retention](./backup-restore#backup-retention)).
   - Git authorship in the source repository. Only the owner of the repository can remove it there.
   - The provider login of a workspace that is installed on the person's own GitHub or GitLab account. It stays as the name of that workspace.

Erasure removes the results and the feedback about the person, also the copies in other people's conversations.
Results and feedback about another developer stay, also when the person commented on that work.
For the other developer's reviews, erasure removes the captured inputs and the hidden memory of Hephaestus.
After the erasure, Hephaestus stops processing the person's exact provider identities.
The [personal-data map](./dsms/personal-data-map.md) gives what each store keeps.

The receipt keeps the acting instance admin, the state, the times and the counts.
It keeps no erased content and no identity of the person.

## Check that it worked

1. For an access request, open the export.
   It has a `stores` entry for each store that the preview listed with rows.
2. For an erasure, open the request.
   It shows **Erasure complete**.
3. Select **Preview data** again for the same identities.
   Only the rows that the personal-data map keeps after an erasure remain.

## Troubleshooting

### An erasure stops

**Cause:** A store could not erase its rows.
After the first temporary database failure, the request stays in `ERASING` with `STORE_ERASURE_RETRY_PENDING`, and the job tries again on its next pass.
A repeated or permanent failure stops the request in `FAILED` with `STORE_ERASURE_FAILED`.
The page shows **Erasure stopped**.

**Fix:**

1. Read the failure code of the request.
2. Correct the failed store, for example the database connection.
3. Select **Resume erasure…** on the same request.
   Do not start a second request: the first request keeps the receipts of the stores that it completed.

**Check:** The request shows **Erasure complete**.

### The export or the erasure refuses the preview

**Cause:** A provider write started, or its result changed, after the preview.
An unconfirmed write, or a comment without an exact locator, also stops the request and names its send or review.

**Fix:**

1. Inspect the named send or review, and the provider locators.
2. Select **Preview data** again.

**Check:** The export or the erasure accepts the new preview.

### A server with evidence is offline

**Cause:** Erasure marks the evidence copies as erased without waiting for a server.
A server that returns later removes its erased local copies in its next cleanup pass.

**Fix:**

1. Keep detached evidence volumes under access control until their server removed the erased copies.
2. Before you reuse a volume, start its server, and let its cleanup pass complete.
3. For a pending sandbox deletion, list the SandboxClaims: `kubectl --namespace <sandbox namespace> get sandboxclaims`.
4. If reviews stay off, delete the SandboxClaim `agent-<sandboxId>` of the erased attempt.

**Check:** `evidence_reconcile_last_success_seconds` is less than one minute old on each server, and the SandboxClaim and its Pod are gone.
