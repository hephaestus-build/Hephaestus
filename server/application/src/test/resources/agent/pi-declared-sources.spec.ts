import assert from "node:assert/strict";
import test from "node:test";

import type { ToolCallEvent, ToolCallEventResult } from "@earendil-works/pi-coding-agent";

import {
	type DeclaredSources,
	declaredRoots,
	declaredSourcesExtension,
	readableKinds,
	refusedContextRead,
	undeclaredCitedKinds,
} from "../../../main/resources/agent/pi-declared-sources.ts";

const CWD = "/workspace";
const CORE = "scm.pull-request.core";
const DIFF = "scm.pull-request.diff";
const COMMENTS = "scm.pull-request.comments";

function sources(declared: readonly string[]): DeclaredSources {
	return {
		readable: readableKinds(declared),
		fileKinds: new Map([
			["context/metadata.json", CORE],
			["context/description.md", CORE],
			["context/comments.json", COMMENTS],
			["context/linked_work_items.json", "scm.linked-work-items"],
		]),
		contextRoot: "context",
		repositoryRoot: "repos/reviewed",
	};
}

void test("a diff-only practice cannot read the description, and its refusal names what it may read", () => {
	const diffOnly = sources([DIFF]);
	const refusal = refusedContextRead("read", "context/description.md", CWD, diffOnly);
	assert.match(refusal ?? "", /`scm\.pull-request\.core`, which this practice does not declare/u);
	assert.match(refusal ?? "", /scm\.pull-request\.diff/u);
	// The derived description view belongs to the description's kind, not to the change.
	assert.notEqual(
		undefined,
		refusedContextRead("read", "work/change/description.authored.md", CWD, diffOnly),
	);
	assert.equal(refusedContextRead("read", "work/change/diff.patch", CWD, diffOnly), undefined);
	assert.equal(refusedContextRead("read", "repos/reviewed/src/a.ts", CWD, diffOnly), undefined);
});

void test("a declared captured file is readable and searchable, by a relative or an absolute path", () => {
	const withCore = sources([CORE, DIFF]);
	assert.equal(refusedContextRead("read", "context/metadata.json", CWD, withCore), undefined);
	assert.equal(
		refusedContextRead("read", "/workspace/context/description.md", CWD, withCore),
		undefined,
	);
	assert.equal(refusedContextRead("grep", "context/description.md", CWD, withCore), undefined);
	assert.notEqual(undefined, refusedContextRead("read", "context/comments.json", CWD, withCore));
});

void test("a search over a directory that holds captured files is refused; one over the checkout runs", () => {
	const withCore = sources([CORE, DIFF]);
	for (const target of [undefined, ".", "context", "/workspace"]) {
		const refusal = refusedContextRead("grep", target, CWD, withCore);
		assert.match(refusal ?? "", /would also search captured sources/u, String(target));
		assert.match(refusal ?? "", /`repos\/reviewed`/u);
	}
	assert.notEqual(undefined, refusedContextRead("find", "context", CWD, withCore));
	assert.equal(refusedContextRead("grep", "repos/reviewed", CWD, withCore), undefined);
	// A listing shows names, never content.
	assert.equal(refusedContextRead("ls", "context", CWD, withCore), undefined);
});

void test("a linked item's text belongs to the linked-items record", () => {
	assert.notEqual(
		undefined,
		refusedContextRead("read", "context/linked_work_items/3.md", CWD, sources([DIFF])),
	);
	assert.equal(
		refusedContextRead(
			"read",
			"context/linked_work_items/3.md",
			CWD,
			sources(["scm.linked-work-items"]),
		),
		undefined,
	);
});

void test("the declared roots are the change, the declared captured files and the checkout", () => {
	assert.deepEqual(declaredRoots(sources([CORE, DIFF])), [
		"context/description.md",
		"context/metadata.json",
		"repos/reviewed",
		"work/change",
	]);
	// Every session reaches the change through Git, so its view is a root for every practice.
	assert.deepEqual(declaredRoots(sources([COMMENTS])), [
		"context/comments.json",
		"repos/reviewed",
		"work/change",
	]);
});

void test("a result that cites or consulted an undeclared kind names it", () => {
	const candidate = {
		outcome: "NOT_MET",
		citations: [{ sourceKind: DIFF }],
		problems: [{ citations: [{ sourceKind: CORE }] }],
		evidence: { search: { consulted: [COMMENTS, DIFF] } },
	};
	const inapplicable = {
		outcome: "DOES_NOT_APPLY",
		citations: [{ sourceKind: DIFF }],
		evidence: { inapplicability: { consulted: [CORE] } },
	};
	assert.deepEqual(undeclaredCitedKinds(inapplicable, readableKinds([])), [CORE]);
	assert.deepEqual(undeclaredCitedKinds(candidate, readableKinds([])), [COMMENTS, CORE]);
	assert.deepEqual(undeclaredCitedKinds(candidate, readableKinds([CORE, COMMENTS])), []);
	// The checkout is readable from every session.
	assert.deepEqual(
		undeclaredCitedKinds({ citations: [{ sourceKind: "scm.repository.tree" }] }, readableKinds([])),
		[],
	);
});

void test("a source used by Applies when remains outside a diff-only session's reads", () => {
	// Capture can use comments to decide whether a practice runs. The runtime still receives only Reads.
	const declared = readableKinds([DIFF]);
	for (const candidate of [
		{ citations: [{ sourceKind: COMMENTS }] },
		{ problems: [{ citations: [{ sourceKind: COMMENTS }] }] },
		{ evidence: { search: { consulted: [COMMENTS] } } },
		{ evidence: { inapplicability: { consulted: [COMMENTS] } } },
	]) {
		assert.deepEqual(undeclaredCitedKinds(candidate, declared), [COMMENTS]);
		assert.deepEqual(undeclaredCitedKinds(candidate, readableKinds([DIFF, COMMENTS])), []);
	}
});

void test("the guard reads the path the tool opens: an @ prefix does not get past it", () => {
	const diffOnly = sources([DIFF]);
	assert.notEqual(undefined, refusedContextRead("read", "@context/comments.json", CWD, diffOnly));
	assert.notEqual(undefined, refusedContextRead("grep", "@context", CWD, diffOnly));
});

void test("a path that no captured source owns is read, and a captured file outside the context root is guarded", () => {
	const withChat: DeclaredSources = {
		...sources([DIFF]),
		fileKinds: new Map([["context/chat/thread.json", "slack.conversation.thread"]]),
	};
	assert.equal(refusedContextRead("read", "context/change.json", CWD, withChat), undefined);
	assert.notEqual(undefined, refusedContextRead("read", "context/chat/thread.json", CWD, withChat));
	assert.notEqual(undefined, refusedContextRead("grep", "context/chat", CWD, withChat));
	assert.equal(refusedContextRead("grep", "context/scm", CWD, withChat), undefined);
});

void test("the guard blocks a read the session makes directly and one that codemode makes for it", () => {
	let handler: ((event: ToolCallEvent) => ToolCallEventResult | undefined) | undefined;
	declaredSourcesExtension(
		sources([DIFF]),
		CWD,
	)({
		on: (_event, registered) => {
			handler = registered;
		},
	});
	assert.ok(handler);
	const direct = handler({
		type: "tool_call",
		toolCallId: "1",
		toolName: "read",
		input: { path: "context/comments.json" },
	});
	assert.deepEqual(direct, {
		block: true,
		reason:
			"context/comments.json is `scm.pull-request.comments`, which this practice does not declare. Read only: scm.pull-request.diff, scm.repository.tree.",
	});
	// Pi fires the same event for a codemode script's call, with the script's call as its parent.
	const nested = handler({
		type: "tool_call",
		toolCallId: "2/1",
		parentToolCallId: "2",
		toolName: "grep",
		input: { pattern: "nit", path: "context" },
	});
	assert.equal(nested?.block, true);
	assert.equal(
		handler({
			type: "tool_call",
			toolCallId: "3",
			toolName: "bash",
			input: { command: "cat context/comments.json" },
		}),
		undefined,
	);
	assert.equal(
		handler({
			type: "tool_call",
			toolCallId: "4",
			toolName: "read",
			input: { path: "work/change/diff.patch" },
		}),
		undefined,
	);
});
