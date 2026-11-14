---
title: Remove feedback from a provider
description: Withdraw a posted comment in the web app, or find every copy of a piece of feedback and delete it at the provider before an erasure or a purge.
page_type: how-to
---

Remove feedback from a provider when a comment that Hephaestus posted on the work must go, for example after wrong feedback.
Start in the web app: it withdraws the comment and keeps a way back.
Delete the comment at the provider only for an erasure, or when the web app cannot change the comment.
Deleting data in Hephaestus does not delete a provider comment.

## Before you start

- For a withdrawal in the web app, you are a workspace admin of the affected workspace.
- For a deletion at the provider, you are also an instance admin, and you have:
  - Read-only access to the application database.
  - Provider credentials that can delete the comments of the Hephaestus app, in a session apart from Hephaestus.
  - A restricted incident record for the results.

## Withdraw the comment in the web app

A workspace admin can withdraw feedback on the work in GitHub and GitLab.
Hephaestus edits each posted comment to say `This feedback was withdrawn.`
Silent mode also stops this edit.
If silent mode is on, check the comment again after you turn silent mode off.

1. Open the review under **Practices → Practice reviews → Reviews** in the workspace administration.
2. Choose the correction that fits:
   - If the observation was wrong, select **Mark incorrect** on the observation.
     Give a reason, and confirm.
     Hephaestus withdraws each comment that rests on that observation.
   - If the observation was right but the words were wrong, select **Withdraw feedback** on the feedback.
     Give a reason, and confirm.
3. On the provider, open each comment.
   It says `This feedback was withdrawn.`
4. If a comment still shows the feedback, delete it at the provider, as the next sections describe.
   Do the same if the review says that the provider cannot correct the copy.

To undo a mark, select **Remove mark**.
Hephaestus puts the original words back in the same comments, and posts nothing again.
To undo a withdrawal, select **Restore feedback**.
The feedback goes out again only after every check passes.
[Correct observations and feedback](./practice-review#correct-observations-and-feedback) describes both corrections.

Slack messages and Hephaestus answers have no withdrawal. Delete them at the provider.

Finish the provider deletion before a workspace purge, a disconnect or a person erasure, because they remove the records that locate each copy.

## Stop new copies

1. Turn on silent mode, as [Contain an incident](./contain-an-incident#stop-external-writes) describes.
2. In the affected workspace, turn off **Sending feedback → Send feedback** ([Feedback mode, review scope and delivery](./practice-review#feedback-mode-review-scope-and-delivery)).

   The workspace then sends nothing, also after silent mode is off.

## Find every copy

1. Open the review in the web app, and note the workspace ID and the ID of the feedback.
2. List every copy of the feedback, without its text:

   ```sql
   SELECT f.id, f.review_id, f.item_id, f.destination, f.state, f.replaces_id,
          p.id AS copy_id, p.kind, p.state AS copy_state, p.write_started_at, p.write_marker,
          p.external_ref, p.external_url, p.mentor_entry_id, p.mentor_turn_id,
          p.path, p.start_line, p.end_line, p.side, p.posted_at
   FROM feedback f
   LEFT JOIN feedback_copy p ON p.feedback_id = f.id AND p.workspace_id = f.workspace_id
   WHERE f.workspace_id = :'workspace_id'::bigint
     AND f.id = :'feedback_id'::uuid;
   ```

   Run it with `psql`, with the variables `workspace_id` and `feedback_id` set to the IDs of step 1.

3. If `replaces_id` is set, repeat step 2 for the replaced feedback.
4. List the sends of the feedback:

   ```sql
   SELECT kind, state, attempts, stop_reason
   FROM agent_action
   WHERE lane_key = 'feedback:' || :'feedback_id';
   ```

5. Treat each copy in the state `WRITE_STARTED` as a possible provider object, also without an `external_ref`.
   Do the same for each copy of a send that ended `UNCERTAIN`.
6. For such a copy with a `write_marker`, search the provider for the marker.
   Hephaestus appends it to the posted text.
7. For a copy with a `mentor_entry_id`, read `conversation_id`, `turn_id`, `surface` and `slack_ts` of that `mentor_entry` row.
   The surface `APP` has no Slack copy.
8. For a Slack copy, read `slack_team_id` and `slack_channel_id` of the main conversation.
   For a thread, follow `mentor_conversation.parent_conversation_id` to the main conversation.
9. If a Slack copy has no `slack_ts`, find the bot reply with the Slack [conversations.replies](https://docs.slack.dev/reference/methods/conversations.replies/) API.
10. If you cannot see the provider inventory, or it is not clear, record the gap and keep the workspace paused.

## Delete each copy

1. Check the repository or project, the item, the provider account, the author and the text against the provider.
   Do not infer the object type from a numeric ID.
2. Delete the copy with the provider API in the table below.
   Obey the rate limits of the provider.
3. Read back the absence with credentials that can see the object.
   A `404` can mean that the object is hidden, not deleted.
4. After a timeout, read the object again before you retry.
5. Write each feedback ID, provider reference, result, time and operator in the incident record.
   Include each partial failure.

| Copy | Provider API |
| --- | --- |
| GitHub issue or pull request comment | `DELETE /repos/{owner}/{repo}/issues/comments/{comment_id}` ([issue comments](https://docs.github.com/en/rest/issues/comments#delete-an-issue-comment)) |
| GitHub inline review comment | `DELETE /repos/{owner}/{repo}/pulls/comments/{comment_id}` ([review comments](https://docs.github.com/en/rest/pulls/comments#delete-a-review-comment-for-a-pull-request)) |
| GitHub submitted review or approval | Not a comment. Dismiss the review ([reviews](https://docs.github.com/en/rest/pulls/reviews#dismiss-a-review-for-a-pull-request)), and record the text that stays. |
| GitLab issue or merge request note | `DELETE /projects/:id/issues/:issue_iid/notes/:note_id` or `DELETE /projects/:id/merge_requests/:merge_request_iid/notes/:note_id` ([notes](https://docs.gitlab.com/api/notes/)) |
| GitLab discussion or diff note | Its discussion ID and note ID ([discussions](https://docs.gitlab.com/api/discussions/#delete-a-merge-request-discussion-note)) |
| Slack feedback or Hephaestus answer | `chat.delete` with the channel and the message `ts`, and a token that can delete that message ([chat.delete](https://docs.slack.dev/reference/methods/chat.delete/)). The answer must have `ok: true`. |

Outline receives no feedback.
Do not delete source documents instead.

A deletion at the provider does not mark the feedback as retracted in Hephaestus.
It cannot recall emails, screenshots or third-party archives.

## Purge, if requested

1. Check that no copy is live and that no send of the feedback is `ADMITTED` or `EXECUTING`.
2. For a purge, write the workspace ID in your restricted incident record. For a disconnect, write the workspace ID and the connection ID.
   After a database restore, you need them to erase again ([Reapply erasures after a restore](./erasure-recovery)).
3. Purge the workspace, disconnect the provider, or [erase the person](./person-data-requests), as the request requires.

If you cannot prove step 1, keep the workspace paused and do not purge.

## Check that it worked

1. Run the queries of [Find every copy](#find-every-copy) again.
   No send of the feedback is `ADMITTED` or `EXECUTING`.
2. Look up each recorded provider reference.
   Each copy is absent, or recorded in the incident record as kept.

## Undo

A deleted provider comment cannot be restored.
To send feedback again, turn **Send feedback** back on in the workspace, and turn off silent mode, as [Contain an incident](./contain-an-incident#undo) describes.
