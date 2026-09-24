import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";

import { PREVIEW_COMMENT_LIMIT } from "./lib/preview-comment.ts";
import { publishPreviewComments } from "./publish-preview-comments.ts";

const marker = (part = 1, kind = "storybook") =>
	`<!-- Sticky Pull Request Comment${kind}-preview${part > 1 ? `-part-${part}` : ""} -->`;
const comment = (id: number, part = 1, kind = "storybook", login = "github-actions[bot]") => ({
	id,
	body: `Old body\n${marker(part, kind)}`,
	user: { login },
});

async function fixture(t: TestContext, comments = [comment(1)]) {
	const directory = await mkdtemp(path.resolve(tmpdir(), "publish-preview-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const commentsFile = path.resolve(directory, "comments.json");
	const requests: { operation: string; params: Record<string, unknown> }[] = [];
	let nextId = 100;
	let state = "open";
	let failAt = Number.POSITIVE_INFINITY;
	const github = {
		paginate: async <T>(
			method: (params: Record<string, unknown>) => Promise<{ data: T[] }>,
			params: Record<string, unknown>,
		) => {
			const response = await method(params);
			return response.data;
		},
		rest: {
			pulls: { get: async () => ({ data: { state } }) },
			issues: {
				listComments: async () => ({ data: comments }),
				createComment: async (params: Record<string, unknown>) => {
					requests.push({ operation: "create", params });
					if (requests.length === failAt) {
						throw new Error("API unavailable");
					}
					comments.push({
						id: nextId,
						body: String(params.body),
						user: { login: "github-actions[bot]" },
					});
					nextId += 1;
					return { data: {} };
				},
				updateComment: async (params: Record<string, unknown>) => {
					requests.push({ operation: "update", params });
					if (requests.length === failAt) {
						throw new Error("API unavailable");
					}
					const previous = comments.find((entry) => entry.id === params.comment_id);
					assert.ok(previous);
					previous.body = String(params.body);
					return { data: {} };
				},
				deleteComment: async (params: Record<string, unknown>) => {
					requests.push({ operation: "delete", params });
					const index = comments.findIndex((entry) => entry.id === params.comment_id);
					assert.ok(index !== -1);
					comments.splice(index, 1);
					return { data: {} };
				},
			},
		},
	};
	const context = { repo: { owner: "example", repo: "project" }, issue: { number: 7 } };
	const publish = async (teardown = false) =>
		publishPreviewComments({
			github,
			context,
			kind: "storybook",
			...(!teardown && { path: commentsFile }),
		});
	return {
		comments,
		requests,
		publish,
		write: async (value: unknown) => writeFile(commentsFile, JSON.stringify(value)),
		close: () => {
			state = "closed";
		},
		fail: (after = 1) => {
			failAt = requests.length + after;
		},
	};
}

void test("creates all parts in reading order, then updates in place without repeat posts", async (t) => {
	const f = await fixture(t, []);
	await f.write(["First", "Second", "Third"]);
	await f.publish();
	assert.deepEqual(
		f.comments.map((entry) => entry.body),
		[`First\n${marker()}`, `Second\n${marker(2)}`, `Third\n${marker(3)}`],
	);
	f.requests.length = 0;
	await f.publish();
	assert.equal(f.requests.length, 0);
	await f.write(["Updated", "Second", "Third"]);
	await f.publish();
	assert.deepEqual(
		f.requests.map((request) => request.operation),
		["update"],
	);
	assert.equal(f.comments[0]?.id, 100);
});

void test("shrinking replaces the existing sticky comment and removes only owned stale parts", async (t) => {
	const untouched = [
		comment(5, 2, "docs"),
		comment(6, 2, "storybook", "human"),
		{ ...comment(7), body: `Quoted ${marker(2)}\nNot a marker` },
	];
	const f = await fixture(t, [comment(1), comment(2, 2), comment(3, 12), comment(4), ...untouched]);
	await f.write(["Current"]);
	await f.publish();
	assert.deepEqual(f.comments, [{ ...comment(1), body: `Current\n${marker()}` }, ...untouched]);
	assert.deepEqual(
		f.requests.map((request) => request.operation),
		["update", "delete", "delete", "delete"],
	);
});

void test("teardown removes continuation comments, including a delayed build after closure", async (t) => {
	for (const delayedBuild of [false, true]) {
		const f = await fixture(t, [comment(1), comment(2, 2)]);
		await f.write(["Live preview", "More live links"]);
		f.close();
		await f.publish(!delayedBuild);
		assert.equal(f.comments.length, 1);
		assert.match(f.comments[0]?.body ?? "", /Preview has been removed/u);
		assert.doesNotMatch(f.comments[0]?.body ?? "", /Live preview|live links/u);
	}
});

void test("validates the entire artifact before changing any comments", async (t) => {
	const f = await fixture(t);
	for (const value of [{}, [], ["Valid", 3], [""], ["x".repeat(PREVIEW_COMMENT_LIMIT + 1)]]) {
		await f.write(value);
		await assert.rejects(f.publish());
		assert.equal(f.requests.length, 0);
	}
});

void test("an API failure propagates and never deletes still-needed previous parts", async (t) => {
	const f = await fixture(t, [comment(1), comment(2, 2)]);
	await f.write(["New"]);
	f.fail();
	await assert.rejects(f.publish(), /API unavailable/u);
	assert.equal(f.comments.length, 2);
	assert.deepEqual(
		f.requests.map((request) => request.operation),
		["update"],
	);
});

void test("retrying a partially created list converges without duplicate parts", async (t) => {
	const f = await fixture(t, []);
	await f.write(["First", "Second", "Third"]);
	f.fail(2);
	await assert.rejects(f.publish(), /API unavailable/u);
	assert.equal(f.comments.length, 1);
	await f.publish();
	assert.deepEqual(
		f.comments.map((entry) => entry.body),
		[`First\n${marker()}`, `Second\n${marker(2)}`, `Third\n${marker(3)}`],
	);
});
