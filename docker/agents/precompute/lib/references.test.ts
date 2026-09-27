import assert from "node:assert/strict";
import test from "node:test";

import { closingReferences, issueNumberReferences, relatedReferences } from "./references.ts";

void test("closing references use the same issue-number boundary as ordinary references", () => {
	const text =
		"Fixes #12abc, closes #1.2, resolves #42px. Fixes: #7. Also closes #19! <!-- Fixes #8 -->";
	assert.deepEqual(issueNumberReferences(text), [7, 19]);
	assert.deepEqual(closingReferences(text), [7, 19]);
});

void test("a closing keyword counts only in the author's own prose", () => {
	const text = [
		"> Closes #1",
		"",
		"```",
		"Fixes #2",
		"```",
		"",
		"    Resolves #3",
		"",
		"Closes `the draft` #5, and after `code` closes #4",
		"",
		"~~Closes #6~~ Closes ![diagram](x.png) #7 Closes &lt;!-- old --&gt; #8",
	].join("\n");
	assert.deepEqual(closingReferences(text), [4]);
});

void test("a related reference is the author's own phrase directly before a local issue number", () => {
	const text = [
		"Related to #5. related to: #6",
		"Related to other/repo#7, related to #8px <!-- Related to #9 -->",
		"> Related to #10",
		"",
		"> Quoted claim",
		"Related to #16",
		"",
		"    Related to #17",
		"",
		"Related to",
		"",
		"#18 is unrelated.",
		"",
		"Use `Related to #11` for a partial change; related to `the draft` #19.",
		"",
		"~~Related to #20~~ Related to ![diagram](x.png) #21 Related to &lt;!-- old --&gt; #23 ~frontend Related to [#22](https://gitlab.example.com/g/p/-/issues/22) ~backend",
		"",
		"```md",
		"Related to #12",
		"```",
		"Related to #15; see also #13",
		"~~~",
		"Related to #14",
	].join("\n");
	assert.deepEqual(relatedReferences(text), [5, 6, 22, 15]);
});
