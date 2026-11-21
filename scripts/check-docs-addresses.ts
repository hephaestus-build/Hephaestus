/**
 * A released address is a contract with readers: a page path or a heading anchor that a published release lists or
 * links to keeps working. This checks two sources against the docs site:
 * - `docs/released-paths.txt`, the page paths of each released sitemap and the anchors that released files cite;
 * - every `https://docs.hephaestus.build/…` URL in a tracked file, such as MIGRATION.md, the alert rules, the chart
 *   notes and the web app's links.
 * A page passes when the build has it, or a redirect from `docs/src/lib/released-redirects.ts` that leads to it; an
 * anchor passes when the page that it finally reaches has an element with that `id`.
 * The docs build runs it against the built site in `docs/dist`: `node ../scripts/check-docs-addresses.ts`. With
 * `--source`, it reads the site from the docs source without a build (`docs/src/lib/address-index.ts`), so a change
 * that adds or renames a link fails before it is pushed: `vp run gate:docs-addresses`.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const SITE = "https://docs.hephaestus.build/";
const REFRESH = /<meta http-equiv="refresh" content="0;url=(?<target>[^"]+)"/u;

/** What a path of the site serves: a page with its element ids, a redirect, or nothing. */
export type SiteLookup = (
	route: string,
) => { ids: ReadonlySet<string> } | { redirect: string } | undefined;

/** The built site in `docs/dist` as a lookup: `read` returns a built file's text, or `undefined` if there is none. */
export function builtPages(read: (file: string) => string | undefined): SiteLookup {
	const fileOf = (route: string): string | undefined => {
		if (route === "") {
			return read("index.html");
		}
		// A path whose last part has an extension is a file, such as a Markdown twin, an image or `sitemap.xml`.
		if (/\.[\w-]+$/u.test(route.split("/").at(-1) ?? "")) {
			return read(route);
		}
		return read(`${route}.html`) ?? read(`${route}/index.html`);
	};
	return (route) => {
		const html = fileOf(route);
		if (html === undefined) {
			return;
		}
		const target = REFRESH.exec(html)?.groups?.target;
		if (target !== undefined) {
			return { redirect: target };
		}
		return {
			ids: new Set(
				Array.from(
					html.matchAll(/<[a-z][^>]*?\sid="(?<id>[^"]*)"/giu),
					(match) => match.groups?.id ?? "",
				),
			),
		};
	};
}

const normalize = (route: string): string =>
	route.replaceAll(/^\/+|\/+$/gu, "").replace(/^next(?:\/|$)/u, "");

/** The element ids of the page that a path finally reaches, after any redirects, or `undefined` if there is none. */
export function resolvePage(page: string, site: SiteLookup): ReadonlySet<string> | undefined {
	let current = normalize(page);
	for (let hop = 0; hop < 3; hop += 1) {
		const found = site(current);
		if (found === undefined || "ids" in found) {
			return found?.ids;
		}
		current = normalize(found.redirect);
	}
	return undefined;
}

/** The problem with one address (`page` or `page#anchor`), or `undefined` if it resolves. */
export function addressProblem(address: string, site: SiteLookup): string | undefined {
	const [page = "", anchor] = address.split("#", 2);
	const ids = resolvePage(page, site);
	if (ids === undefined) {
		return "no page and no redirect";
	}
	if (anchor === undefined || anchor === "") {
		return undefined;
	}
	// A browser tries the fragment as written, then percent-decoded.
	let decoded = anchor;
	try {
		decoded = decodeURIComponent(anchor);
	} catch {
		// A malformed escape stays as written.
	}
	return ids.has(anchor) || ids.has(decoded) ? undefined : `no element with id "${anchor}"`;
}

/**
 * The paths and ids that the source index claims and the built site lacks. Each would let the source check pass an
 * address that the build refuses.
 */
export function indexDrift(
	index: ReadonlyMap<string, NonNullable<ReturnType<SiteLookup>>>,
	site: SiteLookup,
): string[] {
	return [...index].flatMap(([route, page]) => {
		const built = site(route);
		if (built === undefined) {
			return [`${route}: the source index has this path, the build does not`];
		}
		if (!("ids" in page) || !("ids" in built)) {
			return [];
		}
		const missing = [...page.ids].filter((id) => !built.ids.has(id));
		return missing.length === 0
			? []
			: [`${route}: the source index has ids that the build lacks: ${missing.join(", ")}`];
	});
}

/** The released addresses of `released-paths.txt`, without comments and blank lines. */
export const releasedAddresses = (text: string): string[] =>
	text
		.split("\n")
		.map((line) => line.trim())
		.filter((line) => line !== "" && !line.startsWith("#"));

/** The docs addresses that `git grep -n` lines (`file:line:text`) link to, with where they come from. */
export function linkedAddresses(
	grepLines: readonly string[],
): { where: string; address: string }[] {
	return grepLines.flatMap((entry) => {
		const match = /^(?<file>[^:]+):(?<line>\d+):(?<text>.*)$/u.exec(entry)?.groups;
		if (match?.file === undefined || match.line === undefined || match.text === undefined) {
			return [];
		}
		// A test's fixtures name addresses that no page has, on purpose.
		if (match.file.endsWith(".test.ts")) {
			return [];
		}
		const where = `${match.file}:${match.line}`;
		return [...match.text.matchAll(/https:\/\/docs\.hephaestus\.build\/[\w./%#?=&-]*/gu)].map(
			(link) => {
				// The query does not choose the page or the heading.
				const url = new URL(link[0].replace(/[.,;:]+$/u, ""));
				return { where, address: `${url.pathname.slice(1)}${url.hash}` };
			},
		);
	});
}

if (process.argv[1] === import.meta.filename) {
	const root = path.resolve(import.meta.dirname, "..");
	const { sourcePages } = await import("../docs/src/lib/address-index.ts");
	const pages = await sourcePages(path.join(root, "docs"));
	let site: SiteLookup = (route) => pages.get(route);
	// The docs build also proves the source index against the built site: every path and id that the index
	// claims exists, so the source check never passes an address that the build refuses.
	let drift: string[] = [];
	if (!process.argv.includes("--source")) {
		const build = path.join(root, "docs", "dist");
		site = builtPages((file) => {
			const full = path.join(build, file);
			return existsSync(full) ? readFileSync(full, "utf8") : undefined;
		});
		drift = indexDrift(pages, site);
	}
	const grep = spawnSync(
		"git",
		["grep", "-nIE", String.raw`https://docs\.hephaestus\.build/`, "--", "."],
		{
			cwd: root,
			env: environmentWithoutGitRepository(),
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
		},
	);
	// `git grep` exits 1 when nothing matches.
	if (grep.status !== 0 && grep.status !== 1) {
		throw new Error(`git grep failed: ${grep.stderr}`);
	}
	const failures = [
		...releasedAddresses(readFileSync(path.join(root, "docs", "released-paths.txt"), "utf8")).map(
			(address) => ({
				where: "docs/released-paths.txt",
				address,
			}),
		),
		...linkedAddresses(grep.stdout.split("\n").filter((line) => line !== "")),
	].flatMap(({ where, address }) => {
		const problem = addressProblem(address, site);
		return problem === undefined ? [] : [`${where}: ${SITE}${address} — ${problem}`];
	});
	if (drift.length > 0) {
		console.error(
			`The source address index (docs/src/lib/address-index.ts) differs from the build:\n${drift.map((line) => `  ${line}`).join("\n")}`,
		);
		process.exit(1);
	}
	if (failures.length > 0) {
		console.error(
			`Docs addresses that reach nothing:\n${failures.map((failure) => `  ${failure}`).join("\n")}`,
		);
		process.exit(1);
	}
	console.log("Every released and linked docs address reaches its page and heading.");
}
