#### 🔴 Rewrite custom practice scripts for the public script API

Practice scripts now import only `@hephaestus/practice-scripts`, `@hephaestus/practice-scripts/scm`, `/chat` and `/docs`. The upgrade rewrites every practice whose current script is exact bundled text, whether or not you customized its other fields. Your own script text is not changed, and the upgrade does not stop for it.

Until you rewrite such a script, its practice is withheld with the reason **Not a runnable script**, and the review ends as Partial. It makes no claim about the work.

To find these scripts after the upgrade, run:

```sql
SELECT p.workspace_id, p.slug
FROM practice p
JOIN practice_version v ON v.id = p.current_version_id
WHERE v.definition->>'script' LIKE '%../lib/core/%'
   OR v.definition->>'script' LIKE '%../kits/%';
```

For each script:

1. Replace the imports. `definePracticeScript`, `UnratedError` and `listWithin` come from `@hephaestus/practice-scripts`. `scm` and the SCM helpers come from `@hephaestus/practice-scripts/scm`.
2. Replace `const domain = scm(ctx); expectKind(domain, "scm.pull_request");` with `const work = scm(ctx, "scm.pull_request");`.
3. Replace `requireChange(domain.files)` with `work.changedFiles()`, `domain.files?.head` with `work.head`, and `selectedSources` with `work.sources`.
4. Replace `readCapturedCommits(ctx.evidence)` with `work.commits()`. The other record readers are methods of `work` too.
5. Replace `occurrences` and `scanOccurrences` with `work.sites`.
6. Move any other helper into your script.

**What happens to results.** The rewritten scripts are new practice versions, so their earlier results are no longer current. Nothing reviews again because of the upgrade. A practice is judged again at its next occasion for each item. Results and feedback that you already have stay as they are.

**Scheduled reviews.** The next scheduled batch and repository scan measure their whole scope again, once, because reuse needs the same practice version. This costs model usage once. A scan report cannot compare across this change.

**Reviews in flight.** A review that is queued or running during the upgrade still runs the script version that it pinned. That practice is withheld with the reason above, and its next occasion judges it again.

**Removed.** `branches-from-the-integration-branch` had no use and no catalog entry. A practice with its exact text is withheld with the reason above until you replace its script.
