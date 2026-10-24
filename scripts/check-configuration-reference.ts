/**
 * The committed configuration reference describes every variable an operator can set.
 *
 * It fails when a variable that an application YAML placeholder, a Compose file, or `.env.example`
 * names has no row on `docs/admin/configuration-reference.mdx`, when a row names a variable that no
 * source names any more, and when a row has no description. It reads only the checkout, so it runs
 * without a build. Whether the page matches the metadata of the built JAR is the generator's own
 * drift check in CI.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

import {
	readPage,
	readSources,
	REFERENCE_PAGE,
	REPO_ROOT,
	requiredVariables,
} from "./lib/configuration-reference.ts";

export interface Verdict {
	readonly missing: string[];
	readonly stale: string[];
	readonly undescribed: string[];
}

export function judge(required: ReadonlySet<string>, pageText: string): Verdict {
	const rows = readPage(pageText);
	const onPage = new Set(rows.map((row) => row.variable));
	return {
		missing: [...required].filter((variable) => !onPage.has(variable)).toSorted(),
		stale: [...onPage].filter((variable) => !required.has(variable)).toSorted(),
		undescribed: rows.filter((row) => row.description === "").map((row) => row.variable),
	};
}

function report(title: string, variables: readonly string[], fix: string): void {
	if (variables.length > 0) {
		console.error(
			`${title}:\n${variables.map((variable) => `  ${variable}`).join("\n")}\n  ${fix}\n`,
		);
	}
}

if (import.meta.main) {
	const page = await readFile(path.join(REPO_ROOT, REFERENCE_PAGE), "utf8");
	const { missing, stale, undescribed } = judge(requiredVariables(await readSources()), page);
	report(
		`${REFERENCE_PAGE} has no row for these variables`,
		missing,
		"Run `vp run generate:configuration-reference`. If it reports the variable as unread or unbound, fix the source.",
	);
	report(
		`${REFERENCE_PAGE} has rows for variables that no source names`,
		stale,
		"Run `vp run generate:configuration-reference`.",
	);
	report(
		`${REFERENCE_PAGE} has rows without a description`,
		undescribed,
		"Describe the setting at its source: the @ConfigurationProperties Javadoc, " +
			"additional-spring-configuration-metadata.json for a key without one, or the comment above the key in .env.example. " +
			"Then run `vp run generate:configuration-reference`.",
	);
	if (missing.length + stale.length + undescribed.length > 0) {
		process.exit(1);
	}
	console.log(
		`check-configuration-reference: ${REFERENCE_PAGE} describes every variable the sources name.`,
	);
}
