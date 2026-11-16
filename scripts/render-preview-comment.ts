import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { asRecord, asString, readJsonFile } from "./lib/json.ts";
import { renderPreviewComments, type PreviewLink } from "./lib/preview-comment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

function argument(index: number): string {
	const value = process.argv[index];
	if (!isSet(value)) {
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

const SITE_TITLE_SUFFIX = " | Hephaestus documentation";
const ENTITIES: Record<string, string> = {
	amp: "&",
	lt: "<",
	gt: ">",
	quot: '"',
	"#39": "'",
	"#x27": "'",
};

/**
 * A page's URL is its path under its guide (`docs/admin/upgrade.mdx` → `/admin/upgrade`, an `index` file → its folder).
 * A changed page is published when the build has its HTML file, and the link names the page by its `<title>`.
 */
async function renderDocs(): Promise<string[]> {
	const links = new Map<string, PreviewLink>();
	for (const source of changedFiles) {
		const route = /^docs\/(?<route>(?:user|admin|contributor)\/.+)\.mdx?$/u
			.exec(source)
			?.groups?.route?.replace(/\/index$/u, "");
		if (route === undefined || route.split("/").some((part) => part.startsWith("_"))) {
			continue;
		}
		const file = path.resolve(artifactDirectory, `${route}.html`);
		if (!existsSync(file)) {
			continue;
		}
		const heading =
			/<title>(?<title>[^<]*)<\/title>/u.exec(await readFile(file, "utf8"))?.groups?.title ?? route;
		const title = heading
			.replace(SITE_TITLE_SUFFIX, "")
			.replaceAll(
				/&(?<name>amp|lt|gt|quot|#39|#x27);/gu,
				(_, name: string) => ENTITIES[name] ?? "",
			);
		const url = new URL(route, baseUrl);
		links.set(url.href, { group: path.posix.dirname(source), title, url: url.href });
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

if (kind !== "docs" && kind !== "storybook") {
	throw new Error(`Unknown preview kind: ${kind}`);
}
const rendered = kind === "docs" ? await renderDocs() : await renderStorybook();
await writeFile(output, `${JSON.stringify(rendered)}\n`);
