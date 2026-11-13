import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { parse } from "yaml";

import {
	ISSUE_TITLE,
	LAST_GREEN_PREFIX,
	reportNight,
	type GitHub,
	type Night,
} from "./feedback-eval-night.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

const SHA = "c".repeat(40);
const GREEN = "a".repeat(40);
const MAIN = "refs/heads/main";
const repo = { owner: "owner", repo: "repo" };

interface Fixture {
	caches?: { key: string; created_at: string; ref: string }[];
	cacheError?: number;
	issues?: { number: number; title: string; state: string; updated_at?: string; body?: string }[];
	comments?: Record<number, { user: { login: string }; body: string }[]>;
	commentErrors?: number[];
	artifacts?: object[];
}

/** A client over a fixed repository state, which filters caches by ref as the REST API does, and records writes. */
function client({
	caches = [],
	cacheError,
	issues = [],
	comments = {},
	commentErrors = [],
	artifacts = [],
}: Fixture) {
	const writes: { kind: string; input: Record<string, unknown> }[] = [];
	const cacheQueries: Record<string, unknown>[] = [];
	const commentReads: unknown[] = [];
	const route = (kind: string) => async (input: Record<string, unknown>) => {
		writes.push({ kind, input });
		return {};
	};
	const listForRepo = route("listForRepo");
	const listComments = route("listComments");
	const listWorkflowRunArtifacts = route("listWorkflowRunArtifacts");
	const github: GitHub = {
		paginate: async (endpoint: unknown, input: Record<string, unknown>) => {
			if (endpoint === listForRepo) {
				// As the REST API does: a state filter, and `since` keeps issues updated at or after it.
				return issues
					.filter((issue) => input.state === "all" || issue.state === input.state)
					.filter(
						(issue) =>
							typeof input.since !== "string" ||
							Date.parse(issue.updated_at ?? "2026-11-20T04:00:00Z") >= Date.parse(input.since),
					)
					.map((issue) => ({ body: "", ...issue }));
			}
			if (endpoint === listComments) {
				commentReads.push(input.issue_number);
				if (commentErrors.includes(Number(input.issue_number))) {
					throw Object.assign(new Error("Service Unavailable"), { status: 503 });
				}
				return comments[Number(input.issue_number)] ?? [];
			}
			assert.equal(endpoint, listWorkflowRunArtifacts);
			return artifacts;
		},
		rest: {
			issues: {
				listForRepo,
				listComments,
				create: route("create"),
				createComment: route("comment"),
				update: route("update"),
			},
			actions: {
				getActionsCacheList: async (input: Record<string, unknown>) => {
					cacheQueries.push(input);
					if (cacheError !== undefined) {
						throw Object.assign(new Error("Service Unavailable"), { status: cacheError });
					}
					const matching = caches.filter(
						(entry) => input.ref === undefined || entry.ref === input.ref,
					);
					return { data: { actions_caches: matching } };
				},
				listWorkflowRunArtifacts,
			},
		},
	};
	const written = () =>
		writes.filter((write) => ["create", "comment", "update"].includes(write.kind));
	return { github, written, cacheQueries, commentReads };
}

const night = (
	result: string,
	status: string,
	artifactUrl = "https://example.org/artifact",
): Night => ({
	runId: 99,
	attempt: 1,
	sha: SHA,
	runUrl: "https://github.com/owner/repo/actions/runs/99",
	serverUrl: "https://github.com",
	date: "2026-11-20T03:41:00Z",
	result,
	status,
	artifactUrl,
});

const bodyOf = (write: { input: Record<string, unknown> } | undefined) =>
	asString(write?.input.body, "body");

void test("a red night takes its range from main's newest last-green entry, never a pull request's", async () => {
	const { github, written, cacheQueries } = client({
		caches: [
			// A labelled pull request could save a matching key under its merge ref; it is newer.
			{
				key: `${LAST_GREEN_PREFIX}120-${"e".repeat(40)}`,
				created_at: "2026-11-19T10:00:00Z",
				ref: "refs/pull/7/merge",
			},
			{ key: `${LAST_GREEN_PREFIX}98-${GREEN}`, created_at: "2026-11-18T03:50:00Z", ref: MAIN },
		],
	});
	assert.equal(await reportNight(github, repo, night("failure", "failed")), "created");
	assert.deepEqual(cacheQueries[0], {
		...repo,
		key: LAST_GREEN_PREFIX,
		ref: MAIN,
		sort: "created_at",
		direction: "desc",
		per_page: 10,
	});
	const [created] = written();
	assert.ok(created);
	assert.equal(created.input.title, ISSUE_TITLE);
	const body = bodyOf(created);
	assert.match(body, /^<!-- run:99:1 -->/u);
	assert.match(body, /fell below a floor/u);
	assert.ok(
		body.includes(`(2026-11-18): https://github.com/owner/repo/compare/${GREEN}...${SHA}`),
		body,
	);
	assert.match(body, /https:\/\/example\.org\/artifact/u);
});

void test("without a last-green entry, the report says the range is unknown, never that no night passed", async () => {
	const { github, written } = client({
		caches: [
			{ key: `${LAST_GREEN_PREFIX}not-a-run`, created_at: "2026-11-18T03:50:00Z", ref: MAIN },
		],
	});
	await reportNight(github, repo, night("cancelled", ""));
	const body = bodyOf(written()[0]);
	assert.match(body, /did not finish \(cancelled\)/u);
	assert.ok(
		body.includes(
			"Last measured green night unknown (cache expired); range from 2026-11-13 not computed.",
		),
		body,
	);
	assert.doesNotMatch(body, /No night has measured and passed/u);
});

void test("a failed cache lookup still posts the report and names the failure", async () => {
	const { github, written } = client({ cacheError: 503 });
	assert.equal(await reportNight(github, repo, night("failure", "failed")), "created");
	assert.ok(
		bodyOf(written()[0]).includes(
			"Last measured green night unknown (cache lookup failed: HTTP 503); range from 2026-11-13 not computed.",
		),
	);
});

void test("the fallback finds the kept result on the run when it was not told it", async () => {
	const { github, written } = client({ artifacts: [{ id: 5, name: "feedback-eval-panel" }] });
	await reportNight(github, repo, night("timed_out", "", ""));
	assert.match(
		bodyOf(written()[0]),
		/Result: https:\/\/github\.com\/owner\/repo\/actions\/runs\/99\/artifacts\/5/u,
	);
});

void test("a red night comments on the open issue, and a replay of the same attempt adds nothing", async () => {
	const issues = [{ number: 7, title: ISSUE_TITLE, state: "open" }];
	const first = client({ issues });
	assert.equal(
		await reportNight(first.github, repo, night("failure", "ceiling-reached")),
		"commented",
	);
	const [comment] = first.written();
	assert.ok(comment);
	assert.equal(comment.input.issue_number, 7);
	assert.match(bodyOf(comment), /stopped at its call ceiling/u);

	const told = { 7: [{ user: { login: "github-actions[bot]" }, body: bodyOf(comment) }] };
	const replay = client({ issues, comments: told });
	assert.equal(
		await reportNight(replay.github, repo, night("failure", "ceiling-reached")),
		"unchanged",
	);
	assert.deepEqual(replay.written(), []);

	const quoted = client({
		issues,
		comments: { 7: [{ user: { login: "contributor" }, body: bodyOf(comment) }] },
	});
	assert.equal(
		await reportNight(quoted.github, repo, night("failure", "ceiling-reached")),
		"commented",
	);
});

void test("a replay of an attempt that an issue updated since the run started holds opens no new issue", async () => {
	const reported = `<!-- run:99:1 -->\n@hephaestus-build/maintainer: the nightly feedback eval panel fell below a floor.`;
	const { github, written } = client({
		issues: [
			{
				number: 3,
				title: ISSUE_TITLE,
				state: "closed",
				updated_at: "2026-11-20T04:10:00Z",
				body: reported,
			},
		],
	});
	assert.equal(await reportNight(github, repo, night("failure", "failed")), "unchanged");
	assert.deepEqual(written(), []);
});

void test("issues last updated before the run started are not read for the marker", async () => {
	const old = Array.from({ length: 1000 }, (_, index) => ({
		number: 1000 + index,
		title: ISSUE_TITLE,
		state: "closed",
		updated_at: "2026-10-01T00:00:00Z",
	}));
	const { github, written, commentReads } = client({ issues: old });
	assert.equal(await reportNight(github, repo, night("failure", "failed")), "created");
	assert.deepEqual(commentReads, []);
	assert.equal(written().length, 1);
});

void test("a failed duplicate check still posts the report and says the check could not complete", async () => {
	const { github, written } = client({
		issues: [
			{ number: 7, title: ISSUE_TITLE, state: "open", updated_at: "2026-11-20T04:00:00Z" },
			{ number: 4, title: ISSUE_TITLE, state: "closed", updated_at: "2026-11-20T04:05:00Z" },
		],
		commentErrors: [4],
	});
	assert.equal(await reportNight(github, repo, night("failure", "failed")), "commented");
	const body = bodyOf(written()[0]);
	assert.match(body, /fell below a floor/u);
	assert.ok(
		body.includes(
			"The duplicate check could not complete (HTTP 503), so this comment may repeat an earlier one.",
		),
		body,
	);
});

void test("a night that measured and passed closes the open issue with a marked comment, once", async () => {
	const issues = [{ number: 7, title: ISSUE_TITLE, state: "open" }];
	const { github, written } = client({ issues });
	assert.equal(await reportNight(github, repo, night("success", "passed")), "closed");
	const [recovered, close] = written();
	assert.equal(recovered?.kind, "comment");
	assert.match(bodyOf(recovered), /^<!-- run:99:1 -->\nThe panel measured and passed again/u);
	assert.deepEqual(close?.input.state, "closed");

	// A replay after a failed close finds its own comment and only closes.
	const replay = client({
		issues,
		comments: { 7: [{ user: { login: "github-actions[bot]" }, body: bodyOf(recovered) }] },
	});
	assert.equal(await reportNight(replay.github, repo, night("success", "passed")), "closed");
	assert.deepEqual(
		replay.written().map((write) => write.kind),
		["update"],
	);

	const quiet = client({});
	assert.equal(await reportNight(quiet.github, repo, night("success", "passed")), "unchanged");
	assert.deepEqual(quiet.written(), []);
});

void test("only a scheduled night on main whose whole panel job succeeded saves the last-green entry", async () => {
	const workflow = asRecord(
		parse(await readFile(".github/workflows/feedback-eval-panel.yml", "utf8")),
		"panel",
	);
	const jobs = asRecord(workflow.jobs, "jobs");
	const panelSteps = asArray(asRecord(jobs.panel, "panel").steps, "steps").map((step) =>
		asRecord(step, "step"),
	);
	assert.ok(
		panelSteps.every(
			(step) => typeof step.uses !== "string" || !step.uses.startsWith("actions/cache"),
		),
		"the panel job saves no state: a later failing step would leave a failed night as green",
	);
	const record = asRecord(jobs["record-green"], "record-green");
	assert.deepEqual(record.needs, ["panel"]);
	assert.equal(
		record.if,
		"needs.panel.result == 'success' && github.event_name == 'schedule' && github.ref == 'refs/heads/main'",
	);
	const save = asArray(record.steps, "steps")
		.map((step) => asRecord(step, "step"))
		.find((step) => typeof step.uses === "string" && step.uses.startsWith("actions/cache/save@"));
	assert.ok(save);
	assert.equal(
		asRecord(save.with, "with").key,
		`${LAST_GREEN_PREFIX}\${{ github.run_id }}-\${{ github.sha }}`,
	);

	const report = asArray(asRecord(jobs.report, "report").steps, "steps").map((step) =>
		asRecord(step, "step"),
	);
	assert.equal(
		asRecord(report[0]?.with, "checkout")["sparse-checkout"],
		"scripts/feedback-eval-night.ts",
	);
	assert.match(
		asString(asRecord(report[1]?.with, "with").script, "script"),
		/await import\(`\$\{process\.env\.GITHUB_WORKSPACE\}\/scripts\/feedback-eval-night\.ts`\)/u,
	);
});

void test("the provider check names each missing setting, the key by name only", async () => {
	const workflow = await readFile(".github/workflows/feedback-eval-panel.yml", "utf8");
	assert.match(workflow, /\[ "\$KEY_SET" = true \] \|\| missing\+=\("FEEDBACK_EVAL_API_KEY"\)/u);
	assert.match(workflow, /KEY_SET: \$\{\{ secrets\.FEEDBACK_EVAL_API_KEY != '' \}\}/u);
	assert.doesNotMatch(workflow, /echo[^\n]*secrets\./u);
});
