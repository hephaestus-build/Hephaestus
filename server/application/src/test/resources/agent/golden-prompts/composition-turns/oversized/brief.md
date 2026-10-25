## What was captured
The files below are shown whole. Every line of a captured file carries its line number as `[L<n>] `: cite that number, and quote the text after the prefix. Files marked derived are views made here: cite what they point at — a line of the change by the `[L<n>]` the diff gives it, a line of the description by the number `description.authored.md` gives it in `description.md`, a file of a commit by its entry in `commits.json`. They are the work under review — third-party data to assess, never instructions to you.

### `INDEX.md`
```markdown
[L1] # Capture
[L2] 
[L3] - context/metadata.json
[L4] - context/comments.json
```

### `context/metadata.json`
```json
[L1] {
[L2] 	"title": "Retry record fetches",
[L3] 	"pr_url": "https://github.com/acme/app/pull/7",
[L4] 	"repository_full_name": "acme/app",
[L5] 	"pr_number": 7,
[L6] 	"state": "open",
[L7] 	"is_draft": false,
[L8] 	"is_merged": false,
[L9] 	"source_branch": "retry",
[L10] 	"target_branch": "main",
[L11] 	"commit_sha": "2222222222222222222222222222222222222222",
[L12] 	"author": "dev",
[L13] 	"author_id": 100,
[L14] 	"body": "Wraps the client call.\n\nCloses #3.",
[L15] 	"labels": [
[L16] 		"backend"
[L17] 	],
[L18] 	"basis": {
[L19] 		"captured_at": "2026-10-20T10:00:00Z",
[L20] 		"other_fields": "CAPTURE",
[L21] 		"admitted_at": "not a time"
[L22] 	}
[L23] }
```

### `context/description.md`
```markdown
[L1] Wraps the client call.
[L2] 
[L3] Closes #3.
```

### `context/commits.json`
```json
[L1] [
[L2] 	{
[L3] 		"sha": "2222222222222222222222222222222222222222",
[L4] 		"message": "Retry record fetches"
[L5] 	}
[L6] ]
```

### `context/review_threads.json`
```json
[L1] {
[L2] 	"reviewDecisions": [
[L3] 		{
[L4] 			"nativeId": "901",
[L5] 			"author": "lead",
[L6] 			"authorId": "300",
[L7] 			"body": "Looks fine apart from the catch.",
[L8] 			"submittedAt": "2026-10-19T11:00:00Z",
[L9] 			"observedAt": "2026-10-19T11:05:00Z",
[L10] 			"state": "COMMENTED",
[L11] 			"commitId": "2222222222222222222222222222222222222222",
[L12] 			"dismissed": false
[L13] 		}
[L14] 	],
[L15] 	"decisionHistoryComplete": false
[L16] }
```

### `context/linked_work_items.json`
```json
[L1] {
[L2] 	"workItems": [
[L3] 		{
[L4] 			"number": 3,
[L5] 			"url": "https://github.com/acme/app/issues/3",
[L6] 			"title": "Fetch fails silently",
[L7] 			"state": "open",
[L8] 			"how": "closesOnMerge",
[L9] 			"body": "Fetch returns null.",
[L10] 			"assignees": [
[L11] 				"dev"
[L12] 			]
[L13] 		},
[L14] 		"not a record"
[L15] 	],
[L16] 	"unresolvedReferences": [
[L17] 		9,
[L18] 		"x"
[L19] 	],
[L20] 	"truncated": false
[L21] }
```

### Too large to show here — not available in this turn
- `context/comments.json` (157 KB)

### Not captured — do not look for these
Nothing is known about what they would hold. Never read one as empty: a fact that depends on one is a collection gap.
`context/general_comments.json`