/**
 * Every link inside the built docs site reaches a file of the build, and every `#anchor` reaches an element of its
 * page. The prerender fails on a link to a page that does not exist; this also checks anchors, Markdown twins and
 * static files, on the HTML that ships.
 *
 * `docs/package.json` runs it after the build, on the output in `docs/build`.
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";

import { anchorsOf, missingBuild } from "./check-doc-targets.ts";

const repositoryRoot = path.resolve(import.meta.dirname, "..");

/** The `href` values of the links of a page. The HTML minifier may drop the quotes of a value. */
export function linksOf(html: string): string[] {
	const links: string[] = [];
	for (const match of html.matchAll(
		/<a\s[^>]*?href=(?:"(?<dq>[^"]*)"|'(?<sq>[^']*)'|(?<bare>[^\s"'=<>`]+))/gu,
	)) {
		const href = match.groups?.dq ?? match.groups?.sq ?? match.groups?.bare;
		if (href !== undefined) {
			links.push(href.replaceAll("&amp;", "&"));
		}
	}
	return links;
}

/** The file of the build that a link reaches: a page is `<path>.html`, a static file keeps its name. */
export function targetFile(pathname: string): string {
	const trimmed = pathname.replace(/^\/+/u, "");
	if (trimmed === "" || trimmed.endsWith("/")) {
		return `${trimmed}index.html`;
	}
	return path.posix.extname(trimmed) === "" ? `${trimmed}.html` : trimmed;
}

/** One line for each link of `page` (a path below the build) that reaches no file or no element. */
export function brokenLinks(
	page: string,
	html: string,
	base: string,
	readFile: (file: string) => string | undefined,
): string[] {
	const problems: string[] = [];
	for (const href of linksOf(html)) {
		if (/^(?:[a-z][a-z+.-]*:|\/\/)/iu.test(href)) {
			continue;
		}
		const url = new URL(href, `https://docs.invalid${base}${page}`);
		if (!url.pathname.startsWith(base)) {
			problems.push(`${page}: ${href} leaves the base path ${base}`);
			continue;
		}
		const file = targetFile(decodeURIComponent(url.pathname.slice(base.length)));
		const target = readFile(file);
		const anchor = decodeURIComponent(url.hash.replace(/^#/u, ""));
		if (target === undefined) {
			problems.push(`${page}: ${href} reaches no file (${file})`);
		} else if (anchor !== "" && file.endsWith(".html") && !anchorsOf(target).has(anchor)) {
			problems.push(`${page}: ${href} reaches no element #${anchor}`);
		}
	}
	return problems;
}

function htmlFiles(directory: string, prefix = ""): string[] {
	return readdirSync(path.join(directory, prefix), { withFileTypes: true }).flatMap((entry) => {
		const relative = prefix === "" ? entry.name : `${prefix}/${entry.name}`;
		if (entry.isDirectory()) {
			return htmlFiles(directory, relative);
		}
		return entry.name.endsWith(".html") ? [relative] : [];
	});
}

if (import.meta.main) {
	const buildDirectory = path.join(repositoryRoot, "docs", "build");
	const unbuilt = missingBuild(buildDirectory);
	if (unbuilt !== undefined) {
		console.error(unbuilt);
		process.exit(1);
	}
	const raw = process.env.DOCS_BASE_URL;
	const base = raw === undefined || raw === "" ? "/" : raw;
	const cache = new Map<string, string | undefined>();
	const readFile = (file: string): string | undefined => {
		if (!cache.has(file)) {
			const full = path.join(buildDirectory, file);
			cache.set(file, existsSync(full) ? readFileSync(full, "utf8") : undefined);
		}
		return cache.get(file);
	};
	const pages = htmlFiles(buildDirectory);
	const problems = pages.flatMap((file) =>
		brokenLinks(
			file.replace(/\.html$/u, "").replace(/(?:^|\/)index$/u, ""),
			readFile(file) ?? "",
			base,
			readFile,
		),
	);
	if (problems.length > 0) {
		console.error(
			[
				"Links in the docs site that reach nothing:",
				...problems.map((problem) => `  ${problem}`),
			].join("\n"),
		);
		process.exit(1);
	}
	console.log(`Every link of the ${pages.length} pages reaches its file and element.`);
}
