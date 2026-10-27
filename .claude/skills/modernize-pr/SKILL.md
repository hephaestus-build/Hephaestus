---
name: modernize-pr
description: |
  Bring an old pull request up to date, or continue someone else's pull request.
  Use when asked to rebase, modernize, revive, take over, or finish an existing PR or branch.
allowed-tools:
  - Bash(gh *)
  - Bash(git *)
  - Bash(vp *)
  - Read
  - Edit
  - Grep
  - Glob
metadata:
  source: internal
  version: "1.0.0"
---

# Modernize a pull request

Follow the [writing standard](../../../docs/contributor/simplified-technical-english.md) for all prose.

Modernize the idea, not only the branch.
A clean rebase does not prove that the pull request still belongs in the product.

## Guardrails

- Fetch `origin/main` and the pull request head before you start.
- Changes in the current worktree belong to the maintainer.
  If the worktree is dirty or in use, do the work in a new `git worktree`.
- On your own branch, a request to modernize authorizes a rebase and a push to it.
  It does not authorize a merge, a close, a new base branch, or more scope.
- Never rewrite another person's branch only because someone asked to modernize it.
  Rewrite it in place only with the branch owner's explicit consent, and on a fork only when `maintainerCanModify` is true.
  Otherwise continue in a new pull request (§ Continue someone else's pull request).
- A maintainer's request to take over a pull request authorizes a new branch, a push, and a new pull request.
  It authorizes nothing else.
- Never force-push without a lease on the head SHA that you recorded before the rebase.
- Commits must be signed (`CONTRIBUTING.md` § Signed Commits). A rebase creates each commit again, so keep `commit.gpgsign` on.

## 1. Establish the intent

```bash
PAGER=cat gh pr view <n> --json url,state,baseRefName,headRefName,headRefOid,headRepositoryOwner,isCrossRepository,maintainerCanModify,author,title,body,commits,reviewDecision
```

`headRepositoryOwner` and `isCrossRepository` tell you who owns the branch and which remote holds it.
Record `git log --format='%an <%ae> %s' <merge-base>..<head>`: the authors and commits that a rebase must keep.

Read the title, the body, the linked issues, the review threads, and the complete diff.
State the smallest goal that the pull request tries to reach before you judge how it reaches it.
Record the merge base, so that you can tell the pull request's changes from later changes on `main`.

## 2. Check what changed around it

Read what landed on `main` since the merge base, and search merged and open pull requests for the same paths or behavior.

- Did another change already solve the same problem?
- Did an API, a type, a schema, or a module boundary change?
- Would the pull request bring back old behavior, or add a second way to do the same thing?
- Is the benefit still larger than the cost to maintain it?

No conflicts in Git does not mean that the pull request is current.

## 3. Choose a disposition before you push

| Disposition | When | Then |
|---|---|---|
| **MODERNIZE** | The goal is useful and the implementation still has the right shape | Rebase, resolve, verify, push |
| **REWORK** | The goal is useful, but a rebase would keep the wrong implementation | Describe the smallest rewrite and stop, unless you were asked to do it |
| **CLOSE** | Superseded, obsolete, or no longer worth its cost | Cite the evidence and stop. Close it only with permission. |

Age is not evidence.
Effort already spent is not a reason to keep a pull request.

## 4. Rebase without losing intent

1. Start a branch at the recorded head, and rebase it onto `origin/main`.
2. Resolve each conflict for the pull request's intent and `main`'s newer rules.
   If a conflict shows a product or architecture disagreement, stop and choose the disposition again.
3. Do not resolve a conflict in a generated file. Take `main`'s version, then run its generator again.
4. `db/changelog/**` that reached `main` never changes, and `master.xml` is append-only.
   Keep the branch's one changelog last.
   If it conflicts, run `vp run db:draft-changelog` again, which also refreshes the ERD.
5. Keep the pull request's `.changeset/*.md` and `.migration/*.md` files.
6. Compare the commits before and after with `git range-diff`, and the author list with the one you recorded.
   Confirm that no intended behavior disappeared and that no behavior that `main` removed came back.
   A rebase silently drops a commit whose change is already on `main`. Name each dropped commit in your report.
   No commit by another author is squashed or reworded.
7. Run `vp run format`, then `vp run check` and the tests for the paths that the pull request changes.
8. Push with the lease to the remote that holds the branch, `origin` for a branch in this repository:
   `git push --force-with-lease=<branch>:<recorded-sha> <remote> HEAD:<branch>`.
   If the lease fails, fetch and judge again. Never overwrite new work.

Then hand off to `/watch-pr`.

## Continue someone else's pull request

You finish the original goal on a new branch.
Credit stays in their commits on the original pull request and in the body of yours.
Do not defend an implementation only because someone already wrote it.

- Read the pull request, its review findings, and later corrections from the maintainer.
  Treat a handoff note as context, not as proof. Check it against the code at the current head.
- `Validate PR` requires each commit's author to be the pull request's opener or a maintainer.
  Thus, do not carry their commits over as they are.
  Start a branch from `origin/main`, apply their change (`git cherry-pick --no-commit <commits>`), and commit it under your own identity.
  Add your fixes as separate commits.
- Name each original contributor in the new pull request's body, with the identity from their commits.
  Link the original pull request.
  Never guess an email address.
- Change the original pull request only with its owner's consent.
- Verify the original problem and the real workflow yourself. Green mocked tests do not prove that the workflow works.
- Open the pull request with `/land-pr`, which also hands off to `/watch-pr`.

## Report

Lead with the disposition and the reason. Then state:

- What changed on `main` that matters.
- The conceptual conflicts, or that you found none.
- The rebase and push result.
- The checks that you ran and their results.
- What is still open, with the link to the pull request.

Keep what you observed apart from what you judge.
Say what you could not verify.
