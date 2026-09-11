import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { after, test } from "node:test";

import { fromMarkdown } from "mdast-util-from-markdown";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asStringArray, parseJson } from "./lib/json.ts";
import { PREVIEW_COMMENT_LIMIT, renderPreviewComments } from "./lib/preview-comment.ts";

const roots: string[] = [];
after(async () => {
	await Promise.all(roots.map((root) => rm(root, { recursive: true, force: true })));
});

const script = resolve("scripts/render-preview-comment.ts");

function git(root: string, ...arguments_: string[]): string {
	return execFileSync("git", arguments_, {
		cwd: root,
		encoding: "utf8",
		env: environmentForGitFixture(),
	}).trim();
}

async function repository(...changedFiles: string[]): Promise<{ base: string; root: string }> {
	const root = await mkdtemp(resolve(tmpdir(), "preview-comment-"));
	roots.push(root);
	git(root, "init", "--quiet");
	git(root, "config", "user.email", "test@example.invalid");
	git(root, "config", "user.name", "Test");
	await writeFile(resolve(root, "seed"), "seed");
	git(root, "add", ".");
	git(root, "commit", "--quiet", "-m", "seed");
	const base = git(root, "rev-parse", "HEAD");
	for (const changedFile of changedFiles) {
		await mkdir(dirname(resolve(root, changedFile)), { recursive: true });
		await writeFile(resolve(root, changedFile), "changed");
	}
	git(root, "add", ".");
	git(root, "commit", "--quiet", "-m", "change");
	return { base, root };
}

async function render(
	root: string,
	base: string,
	kind: string,
	directory: string,
): Promise<string> {
	execFileSync(
		"node",
		[script, kind, directory, "https://preview.example/", base, "comments.json"],
		{
			cwd: root,
			stdio: "pipe",
			env: environmentForGitFixture({
				GITHUB_SERVER_URL: "https://github.com",
				GITHUB_REPOSITORY: "example/project",
				GITHUB_RUN_ID: "123",
				GITHUB_SHA: "not-the-checked-out-commit",
			}),
		},
	);
	return asStringArray(
		parseJson(await readFile(resolve(root, "comments.json"), "utf8")),
		"comments",
	).join("\n");
}

void test("links only stories from changed files to their canvases", async () => {
	const { base, root } = await repository("webapp/src/Button.stories.tsx");
	await mkdir(resolve(root, "build"));
	await writeFile(
		resolve(root, "build/index.json"),
		JSON.stringify({
			entries: {
				"button--primary": {
					importPath: "./src/Button.stories.tsx",
					name: "Primary [default]",
					title: "UI/Button",
					type: "story",
				},
				"button--unsafe": {
					importPath: "./src/Button.stories.tsx",
					name: "Unsafe\n<img>",
					title: "UI/Button",
					type: "story",
				},
				"button--docs": {
					importPath: "./src/Button.stories.tsx",
					name: "Docs",
					title: "UI/Button",
					type: "docs",
				},
				"other--unchanged": {
					importPath: "./src/Other.stories.tsx",
					name: "Unchanged",
					title: "Other",
					type: "story",
				},
			},
		}),
	);
	const comment = await render(root, base, "storybook", "build");
	assert.match(comment, /Stories in changed files/);
	assert.ok(comment.includes("#### UI/Button (2)"));
	assert.ok(comment.includes("[Primary \\[default\\]]"));
	assert.match(comment, /\?path=\/story\/button--primary/);
	assert.doesNotMatch(comment, /button--docs/);
	assert.doesNotMatch(comment, /other--unchanged/);
	assert.ok(comment.includes("Unsafe &lt;img&gt;"));
	assert.doesNotMatch(comment, /Unsafe\n/);
	const sha = git(root, "rev-parse", "HEAD");
	assert.ok(
		comment.includes(
			`Built from [\`${sha.slice(0, 7)}\`](<https://github.com/example/project/commit/${sha}>)`,
		),
	);
	assert.ok(
		comment.includes("[Build logs](<https://github.com/example/project/actions/runs/123>)"),
	);
	assert.doesNotMatch(comment, /not-the-checked-out-commit/);
});

void test("shows all 106 stories without truncation or disclosure controls", async () => {
	const { base, root } = await repository("webapp/src/Button.stories.tsx");
	await mkdir(resolve(root, "build"));
	await writeFile(
		resolve(root, "build/index.json"),
		JSON.stringify({
			entries: Object.fromEntries(
				Array.from({ length: 106 }, (_, index) => [
					`button--${index}`,
					{
						importPath: "./src/Button.stories.tsx",
						name: `Example ${index}`,
						title: "Button",
						type: "story",
					},
				]),
			),
		}),
	);
	const comment = await render(root, base, "storybook", "build");
	assert.match(comment, /Stories in changed files \(106\)/);
	assert.match(comment, /#### Button \(106\)/);
	for (let index = 0; index < 106; index++) {
		assert.equal(comment.split(`?path=/story/button--${index}>`).length - 1, 1);
	}
	assert.doesNotMatch(comment, /more are available|<details|<summary|25 of/);
});

void test("does not claim files are unchanged when they have no published stories", async () => {
	const { base, root } = await repository("webapp/src/Button.stories.tsx");
	await mkdir(resolve(root, "build"));
	await writeFile(resolve(root, "build/index.json"), JSON.stringify({ entries: {} }));
	const comment = await render(root, base, "storybook", "build");
	assert.match(
		comment,
		/Stories in changed files\n\nNo published stories found in changed files\./,
	);
});

void test("renders safe changed Docusaurus routes once", async () => {
	const { base, root } = await repository("docs/user/getting-started.mdx");
	await mkdir(resolve(root, "metadata/nested"), { recursive: true });
	await writeFile(
		resolve(root, "metadata/nested/page.json"),
		JSON.stringify({
			permalink: "/user/start-(here)",
			source: "@site/user/getting-started.mdx",
			title: "Start [here]",
		}),
	);
	await writeFile(
		resolve(root, "metadata/nested/duplicate.json"),
		JSON.stringify({
			permalink: "/user/start-(here)",
			source: "@site/user/getting-started.mdx",
			title: "Start [here]",
		}),
	);
	await writeFile(
		resolve(root, "metadata/nested/unchanged.json"),
		JSON.stringify({
			permalink: "/user/other",
			source: "@site/user/other.mdx",
			title: "Unchanged",
		}),
	);
	await writeFile(
		resolve(root, "metadata/nested/external.json"),
		JSON.stringify({
			permalink: "https://attacker.example/phishing",
			source: "@site/user/getting-started.mdx",
			title: "External",
		}),
	);
	const comment = await render(root, base, "docs", "metadata");
	assert.match(comment, /Changed pages/);
	const link = "[Start \\[here\\]](<https://preview.example/user/start-(here)>)";
	assert.equal(comment.split(link).length - 1, 1);
	assert.doesNotMatch(comment, /attacker|External/);
	assert.doesNotMatch(comment, /Unchanged/);
});

void test("rejects an invalid Storybook index instead of publishing an empty result", async () => {
	const { base, root } = await repository("webapp/src/Button.stories.tsx");
	await mkdir(resolve(root, "build"));
	for (const index of [
		{},
		{ entries: [] },
		{ entries: null },
		{ entries: { "button--primary": { type: "story" } } },
	]) {
		await writeFile(resolve(root, "build/index.json"), JSON.stringify(index));
		await assert.rejects(
			render(root, base, "storybook", "build"),
			/Storybook (index\.entries|entry button--primary\.importPath) must be/,
		);
	}
});

void test("does not claim docs are unchanged when a changed page is unpublished", async () => {
	const { base, root } = await repository("docs/user/draft.mdx");
	await mkdir(resolve(root, "metadata"));
	const comment = await render(root, base, "docs", "metadata");
	assert.match(comment, /Changed pages\n\nNo published pages found in changed files\./);
});

void test("groups every changed documentation page by source directory, not its custom route", async () => {
	const sources = Array.from(
		{ length: 35 },
		(_, index) => `docs/${index % 2 ? "user" : "contributor/deep"}/page-${index}.mdx`,
	);
	const { base, root } = await repository(...sources);
	await mkdir(resolve(root, "metadata"));
	await Promise.all(
		sources.map((source, index) =>
			writeFile(
				resolve(root, `metadata/${index}.json`),
				JSON.stringify({
					source: source.replace(/^docs\//, "@site/"),
					title: `Page ${index}`,
					permalink: `/custom/${index}`,
				}),
			),
		),
	);
	const comment = await render(root, base, "docs", "metadata");
	assert.match(comment, /Changed pages \(35\)/);
	assert.match(comment, /#### docs\/contributor\/deep \(18\)/);
	assert.match(comment, /#### docs\/user \(17\)/);
	for (let index = 0; index < sources.length; index++) {
		assert.equal(comment.split(`https://preview.example/custom/${index}>`).length - 1, 1);
	}
});

void test("uses stable, disambiguated groups with complete headings and atomic links across oversized comments", () => {
	const links = Array.from({ length: 1200 }, (_, index) => ({
		group: index < 1000 ? "components/admin/Button" : "components/workspace/Button",
		title: `Story ${index} — 日本語 🧩`,
		url: `https://preview.example/?path=/story/button--${index}`,
	}));
	const renderParts = (entries: typeof links) =>
		renderPreviewComments(
			"## Storybook preview\n\n### Stories in changed files (1200)",
			entries,
			"Empty",
			"Build provenance",
		);
	const comments = renderParts(links);
	assert.ok(comments.length > 1);
	assert.deepEqual(renderParts(links.toReversed()), comments);
	for (const [index, comment] of comments.entries()) {
		assert.ok(comment.length <= PREVIEW_COMMENT_LIMIT);
		assert.ok(comment.includes(`Part ${index + 1} of ${comments.length}`));
		assert.match(comment, /#### components\/(admin|workspace)\/Button \(\d+\)/);
		assert.ok(comment.endsWith("Build provenance\n"));
		assert.doesNotMatch(comment, /<details|<summary/);
	}
	const combined = comments.join("\n");
	for (const { url } of links) assert.equal(combined.split(`](<${url}>)`).length - 1, 1);
});

void test("renders metadata as literal text rather than Markdown or HTML", () => {
	const group = "UI/*Button* #1\n<script><SCRIPT><ScRiPt>";
	const title = "[label](https://evil.example) ![image] `code` ~~strike~~ _em_ | &lt;tag&gt;";
	const [comment] = renderPreviewComments(
		"Preview",
		[
			{
				group,
				title,
				url: "https://preview.example/",
			},
		],
		"Empty",
		"",
	);
	assert.ok(comment?.includes("#### UI/\\*Button\\* \\#1 &lt;script&gt;"));
	assert.ok(comment?.includes("\\`code\\` \\~\\~strike\\~\\~ \\_em\\_ \\| &amp;lt;tag&amp;gt;"));
	assert.ok(comment);
	const nodes = fromMarkdown(comment).children;
	assert.deepEqual(
		nodes.map((node) => node.type),
		["paragraph", "heading", "paragraph"],
	);
	const heading = nodes.find((node) => node.type === "heading");
	assert.ok(heading);
	assert.deepEqual(
		heading.children.map((node) => (node.type === "text" ? node.value : node.type)),
		[`${group.replaceAll("\n", " ")} (1)`],
	);
	const paragraph = nodes.at(-1);
	assert.ok(paragraph?.type === "paragraph");
	assert.deepEqual(
		paragraph.children.map((node) => node.type),
		["link"],
	);
	const link = paragraph.children[0];
	assert.ok(link?.type === "link");
	assert.deepEqual(
		link.children.map((node) => (node.type === "text" ? node.value : node.type)),
		[title],
	);
});

void test("refuses an indivisible oversized link rather than silently dropping it", () => {
	assert.throws(
		() =>
			renderPreviewComments(
				"Preview",
				[
					{
						group: "Button",
						title: "x".repeat(PREVIEW_COMMENT_LIMIT),
						url: "https://preview.example/",
					},
				],
				"Empty",
				"",
			),
		/exceeds GitHub's comment limit/,
	);
});

void test("Markdown parsing preserves literal labels and entity-like URL segments", () => {
	const title = "[label](https://evil.example) `code` *em* <img> &copy;";
	const url = "https://preview.example/a&copy;?x=1&y=2";
	const [body] = renderPreviewComments(
		"## Preview",
		[{ group: "UI/Button", title, url }],
		"Empty",
		"",
	);
	assert.ok(body);
	const paragraphs = fromMarkdown(body).children.filter((node) => node.type === "paragraph");
	const links = paragraphs.flatMap((paragraph) =>
		paragraph.children.filter((node) => node.type === "link"),
	);
	assert.equal(links.length, 1);
	const link = links[0];
	assert.ok(link);
	assert.equal(link.url, url);
	assert.deepEqual(
		link.children.map((node) => (node.type === "text" ? node.value : node.type)),
		[title],
	);
});
