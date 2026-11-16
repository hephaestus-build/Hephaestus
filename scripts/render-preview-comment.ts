import { execFileSync } from "node:child_process";
import { writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { asRecord, asString, readJsonFile } from "./lib/json.ts";
import { renderPreviewComments, type PreviewLink } from "./lib/preview-comment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

function argument(index: number): string {
	const value = process.argv[index];
	if (!isSet(value)) {
		throw new Error(
			"Usage: render-preview-comment <storybook-static-directory> <preview-url> <base-sha> <output>",
		);
	}
	return value;
}

const artifactDirectory = argument(2);
const previewUrl = argument(3);
const baseSha = argument(4);
const output = argument(5);

const changedFiles = new Set(
	execFileSync("git", ["diff", "--name-only", "--diff-filter=ACMRT", "-z", `${baseSha}...HEAD`], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	})
		.split("\0")
		.filter(Boolean),
);

const baseUrl = new URL(previewUrl);

async function renderStorybook(): Promise<string[]> {
	const index = asRecord(
		await readJsonFile(path.resolve(artifactDirectory, "index.json")),
		"Storybook index",
	);
	const links = Object.entries(asRecord(index.entries, "Storybook index.entries")).flatMap(
		([id, value]) => {
			const entry = asRecord(value, `Storybook entry ${id}`);
			if (entry.type !== "story") {
				return [];
			}
			const importPath = asString(entry.importPath, `Storybook entry ${id}.importPath`);
			const name = asString(entry.name, `Storybook entry ${id}.name`);
			const title = asString(entry.title, `Storybook entry ${id}.title`);
			if (!changedFiles.has(`webapp/${importPath.replace(/^\.\//u, "")}`)) {
				return [];
			}
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
	const count = links.length > 0 ? ` (${links.length})` : "";
	const introduction = `## ${heading}\n\n[${previewLabel}](<${baseUrl.href}>)\n\n### ${linksHeading}${count}`;
	let footer = "";
	const { GITHUB_SERVER_URL, GITHUB_REPOSITORY, GITHUB_RUN_ID } = process.env;
	if (isSet(GITHUB_SERVER_URL) && isSet(GITHUB_REPOSITORY) && isSet(GITHUB_RUN_ID)) {
		const sha = execFileSync("git", ["rev-parse", "HEAD"], {
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
		}).trim();
		const repositoryUrl = `${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}`;
		footer = `Built from [\`${sha.slice(0, 7)}\`](<${repositoryUrl}/commit/${sha}>) · [Build logs](<${repositoryUrl}/actions/runs/${GITHUB_RUN_ID}>). Updates after successful preview builds.`;
	}
	return renderPreviewComments(introduction, links, emptyMessage, footer);
}

const rendered = await renderStorybook();
await writeFile(output, `${JSON.stringify(rendered)}\n`);
