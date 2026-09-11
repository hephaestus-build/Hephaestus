import { execFileSync } from "node:child_process";
import { readdir, writeFile } from "node:fs/promises";
import { posix, resolve } from "node:path";

import { asRecord, asString, isRecord, readJsonFile } from "./lib/json.ts";
import { compareLinks, renderPreviewComments, type PreviewLink } from "./lib/preview-comment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

function argument(index: number): string {
	const value = process.argv[index];
	if (!value) {
		throw new Error(
			"Usage: render-preview-comment <docs|storybook> <artifact-directory> <preview-url> <base-sha> <output>",
		);
	}
	return value;
}

const kind = argument(2);
const artifactDirectory = argument(3);
const previewUrl = argument(4);
const baseSha = argument(5);
const output = argument(6);

const changedFiles = new Set(
	execFileSync("git", ["diff", "--name-only", "--diff-filter=ACMRT", "-z", `${baseSha}...HEAD`], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	})
		.split("\0")
		.filter(Boolean),
);

const baseUrl = new URL(previewUrl);

async function renderDocs(): Promise<string[]> {
	const links = new Map<string, PreviewLink>();
	for (const file of await readdir(artifactDirectory, { recursive: true })) {
		if (!file.endsWith(".json")) continue;
		const metadata = await readJsonFile(resolve(artifactDirectory, file));
		if (!isRecord(metadata)) continue;
		const { source: sourcePath, permalink, title } = metadata;
		if (
			typeof sourcePath !== "string" ||
			typeof permalink !== "string" ||
			typeof title !== "string"
		)
			continue;
		const source = `docs/${sourcePath.replace(/^@site\//, "")}`;
		if (changedFiles.has(source)) {
			const url = new URL(permalink, baseUrl);
			const existing = links.get(url.href);
			const link = { group: posix.dirname(source), title, url: url.href };
			if (
				url.origin === baseUrl.origin &&
				(existing === undefined || compareLinks(link, existing) < 0)
			) {
				links.set(url.href, link);
			}
		}
	}
	return comment(
		"📚 Documentation preview",
		"Open full documentation preview",
		[...links.values()],
		"Changed pages",
		"No published pages found in changed files.",
	);
}

async function renderStorybook(): Promise<string[]> {
	const index = asRecord(
		await readJsonFile(resolve(artifactDirectory, "index.json")),
		"Storybook index",
	);
	const links = Object.entries(asRecord(index.entries, "Storybook index.entries")).flatMap(
		([id, value]) => {
			const entry = asRecord(value, `Storybook entry ${id}`);
			if (entry.type !== "story") return [];
			const importPath = asString(entry.importPath, `Storybook entry ${id}.importPath`);
			const name = asString(entry.name, `Storybook entry ${id}.name`);
			const title = asString(entry.title, `Storybook entry ${id}.title`);
			if (!changedFiles.has(`webapp/${importPath.replace(/^\.\//, "")}`)) return [];
			return [
				{
					group: title,
					title: name,
					url: new URL(`?path=/story/${encodeURIComponent(id)}`, baseUrl).href,
				},
			];
		},
	);
	return comment(
		"🧩 Storybook preview",
		"Open full Storybook preview",
		links,
		"Stories in changed files",
		"No published stories found in changed files.",
	);
}

function comment(
	heading: string,
	previewLabel: string,
	links: readonly PreviewLink[],
	linksHeading: string,
	emptyMessage: string,
): string[] {
	const count = links.length ? ` (${links.length})` : "";
	const introduction = `## ${heading}\n\n[${previewLabel}](<${baseUrl.href}>)\n\n### ${linksHeading}${count}`;
	let footer = "";
	const { GITHUB_SERVER_URL, GITHUB_REPOSITORY, GITHUB_RUN_ID } = process.env;
	if (GITHUB_SERVER_URL && GITHUB_REPOSITORY && GITHUB_RUN_ID) {
		const sha = execFileSync("git", ["rev-parse", "HEAD"], {
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
		}).trim();
		const repositoryUrl = `${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}`;
		footer = `Built from [\`${sha.slice(0, 7)}\`](<${repositoryUrl}/commit/${sha}>) · [Build logs](<${repositoryUrl}/actions/runs/${GITHUB_RUN_ID}>). Updates after successful preview builds.`;
	}
	return renderPreviewComments(introduction, links, emptyMessage, footer);
}

const rendered =
	kind === "docs" ? await renderDocs() : kind === "storybook" ? await renderStorybook() : undefined;
if (!rendered) throw new Error(`Unknown preview kind: ${kind}`);
await writeFile(output, `${JSON.stringify(rendered)}\n`);
