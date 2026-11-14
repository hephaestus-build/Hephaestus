/**
 * Every docs page and heading that the web app links to exists in the built docs site.
 * `webapp/src/lib/doc-targets.ts` names them. Docusaurus checks the links inside the site
 * (`onBrokenLinks`, `onBrokenAnchors`), but not an address that another program writes. Thus, a
 * renamed page or heading fails the docs build here instead of breaking a link in the app.
 *
 * `docs/package.json` runs it after `docusaurus build`, on the output in `docs/build`. Run on its own, it reads
 * whatever build is there, so it refuses when there is none instead of reporting every target as missing.
 */
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

// oxlint-disable-next-line import/no-relative-parent-imports -- The web app owns the registry that this build check reads.
import { DOC_TARGETS, type DocTarget } from "../webapp/src/lib/doc-targets.ts";

const repositoryRoot = path.resolve(import.meta.dirname, "..");

/**
 * The `id` attributes of a built page. The HTML minifier drops the quotes of a value that needs
 * none, so all three attribute forms count.
 */
export function anchorsOf(html: string): Set<string> {
	const ids = new Set<string>();
	for (const match of html.matchAll(
		/\sid=(?:"(?<dq>[^"]*)"|'(?<sq>[^']*)'|(?<bare>[^\s"'=<>`]+))/gu,
	)) {
		const id = match.groups?.dq ?? match.groups?.sq ?? match.groups?.bare;
		if (id !== undefined) {
			ids.add(id);
		}
	}
	return ids;
}

/**
 * The page's HTML in the build output, or `undefined` if the site has no such page. With
 * `trailingSlash: false`, Docusaurus writes the page `admin/upgrade` to `admin/upgrade.html`.
 */
export function readBuiltPage(buildDirectory: string, page: string): string | undefined {
	const file = path.join(buildDirectory, `${page}.html`);
	return existsSync(file) ? readFileSync(file, "utf8") : undefined;
}

/** Why the check cannot run, or `undefined` when `buildDirectory` holds a built site. */
export function missingBuild(buildDirectory: string): string | undefined {
	return existsSync(path.join(buildDirectory, "index.html"))
		? undefined
		: `There is no built docs site in ${path.relative(repositoryRoot, buildDirectory)}. ` +
				"Run `vp run docs:build`: it builds the site, then runs this check.";
}

/** One line for each target that the build does not have. */
export function missingTargets(
	targets: Readonly<Record<string, DocTarget>>,
	readPage: (page: string) => string | undefined,
): string[] {
	const problems: string[] = [];
	for (const [key, target] of Object.entries(targets)) {
		const html = readPage(target.path);
		if (html === undefined) {
			problems.push(`${key}: the docs site has no page /${target.path}`);
		} else if (target.anchor !== undefined && !anchorsOf(html).has(target.anchor)) {
			problems.push(`${key}: /${target.path} has no heading #${target.anchor}`);
		}
	}
	return problems;
}

if (import.meta.main) {
	const buildDirectory = path.join(repositoryRoot, "docs", "build");
	const unbuilt = missingBuild(buildDirectory);
	if (unbuilt !== undefined) {
		console.error(unbuilt);
		process.exit(1);
	}
	const problems = missingTargets(DOC_TARGETS, (page) => readBuiltPage(buildDirectory, page));
	if (problems.length > 0) {
		console.error(
			[
				"The web app links to docs that the build does not have (webapp/src/lib/doc-targets.ts):",
				...problems.map((problem) => `  ${problem}`),
				"Update the entry in webapp/src/lib/doc-targets.ts to the page's new address.",
			].join("\n"),
		);
		process.exit(1);
	}
	console.log(`All ${Object.keys(DOC_TARGETS).length} docs targets of the web app exist.`);
}
