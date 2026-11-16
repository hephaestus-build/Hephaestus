/**
 * A released address is a contract with readers: a page path or a heading anchor that a published release lists or
 * links to keeps working. This checks two sources against the built site in `docs/build`:
 * - `docs/released-paths.txt`, the page paths of each released sitemap and the anchors that released files cite;
 * - every `https://docs.hephaestus.build/…` URL in a tracked file, such as MIGRATION.md, the alert rules, the chart
 *   notes and the web app's links.
 * A page passes when the build has it, or a redirect from `docs/src/lib/released-redirects.ts` that leads to it; an
 * anchor passes when the page that it finally reaches has an element with that `id`.
 * Run in `docs/` after the build: `node ../scripts/check-docs-addresses.ts`.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const SITE = "https://docs.hephaestus.build/";
const REFRESH = /<meta http-equiv="refresh" content="0;url=(?<target>[^"]+)"/u;

/** The HTML of a page path (no leading slash), after any redirects, or `undefined` if the build has no such page. */
export function resolvePage(
	page: string,
	read: (file: string) => string | undefined,
): string | undefined {
	const fileOf = (route: string): string | undefined => {
		if (route === "") {
			return read("index.html");
		}
		if (/\.(?:md|txt|xml|png|webp|svg|yml|yaml|json)$/u.test(route)) {
			return read(route);
		}
		return read(`${route}.html`) ?? read(`${route}/index.html`);
	};
	let current = page.replaceAll(/^\/+|\/+$/gu, "").replace(/^next(?:\/|$)/u, "");
	for (let hop = 0; hop < 3; hop += 1) {
		const html = fileOf(current);
		const target = html === undefined ? undefined : REFRESH.exec(html)?.groups?.target;
		if (target === undefined) {
			return html;
		}
		current = target
			.replace(/^\/+/u, "")
			.replace(/^next(?:\/|$)/u, "")
			.replace(/\/+$/u, "");
	}
	return undefined;
}

/** The problem with one address (`page` or `page#anchor`), or `undefined` if it resolves. */
export function addressProblem(
	address: string,
	read: (file: string) => string | undefined,
): string | undefined {
	const [page = "", anchor] = address.split("#", 2);
	const html = resolvePage(page, read);
	if (html === undefined) {
		return "no page and no redirect";
	}
	if (anchor !== undefined && anchor !== "" && !html.includes(`id="${anchor}"`)) {
		return `no element with id "${anchor}"`;
	}
	return undefined;
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
		return [...match.text.matchAll(/https:\/\/docs\.hephaestus\.build\/(?<rest>[\w./%#-]*)/gu)].map(
			(link) => ({
				where,
				address: (link.groups?.rest ?? "").replace(/[.,;:]+$/u, ""),
			}),
		);
	});
}

if (process.argv[1] === import.meta.filename) {
	const root = path.resolve(import.meta.dirname, "..");
	const build = path.join(root, "docs", "build");
	const read = (file: string): string | undefined => {
		const full = path.join(build, file);
		return existsSync(full) ? readFileSync(full, "utf8") : undefined;
	};
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
		const problem = addressProblem(address, read);
		return problem === undefined ? [] : [`${where}: ${SITE}${address} — ${problem}`];
	});
	if (failures.length > 0) {
		console.error(
			`Docs addresses that reach nothing:\n${failures.map((failure) => `  ${failure}`).join("\n")}`,
		);
		process.exit(1);
	}
	console.log("Every released and linked docs address reaches its page and heading.");
}
