/**
 * The committed configuration reference describes every variable an operator can set.
 *
 * It fails when a variable that an application YAML placeholder, the chart schema or a rendered workload
 * names has no row on `docs/admin/configuration-reference.mdx`, when a row names a variable that no
 * source names any more, and when a row has no description. It reads only the checkout, so it runs
 * without a build. Whether the page matches the metadata of the built JAR is the generator's own
 * drift check in CI.
 */
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

import {
	descriptionProblems,
	readPage,
	readSources,
	readReference,
	REFERENCE_PAGE,
	REPO_ROOT,
	requiredVariables,
	type Sources,
} from "./lib/configuration-reference.ts";

export interface Verdict {
	readonly missing: string[];
	readonly stale: string[];
	readonly undescribed: string[];
	/** `VARIABLE: problem` for each description that breaks a rule of descriptionProblems. */
	readonly flawed: string[];
}

export function judge(required: ReadonlySet<string>, pageText: string): Verdict {
	const rows = readPage(pageText);
	const onPage = new Set(rows.map((row) => row.variable));
	return {
		missing: [...required].filter((variable) => !onPage.has(variable)).toSorted(),
		stale: [...onPage].filter((variable) => !required.has(variable)).toSorted(),
		undescribed: rows
			.filter((row) => !row.variable.startsWith("values.") && row.description === "")
			.map((row) => row.variable),
		flawed: rows.flatMap((row) =>
			(row.variable.startsWith("values.") ? [] : descriptionProblems(row.description)).map(
				(problem) => `${row.variable}: ${problem}`,
			),
		),
	};
}

/**
 * Variables that admin docs offer although the chart cannot set them. Each needs a chart value or leaves the operator
 * docs. An entry that the docs no longer offer fails too, so the list only shrinks. Delete it at zero.
 */
export const OFFERED_WITHOUT_CHART_VALUE: ReadonlySet<string> = new Set([
	"AGENT_MAX_RETRIES",
	"GH_APP_PRIVATE_KEY_LOCATION",
	"GIT_MAX_SNAPSHOT_BYTES",
	"HEPHAESTUS_AUTH_ACCESS_TTL",
	"HEPHAESTUS_AUTH_STEP_UP_MAX_AGE",
	"HEPHAESTUS_INTEGRATION_CONSUMER_DEAD_RETENTION",
	"HEPHAESTUS_INTEGRATION_CONSUMER_REPLAY_WINDOW",
	"HEPHAESTUS_LLM_CALL_POOLS_MAX_COOLDOWN",
	"HEPHAESTUS_LLM_EGRESS_ALLOW_LOOPBACK",
	"HEPHAESTUS_LLM_FX_DAILY_URL",
	"ITEM_EVENT_LEDGER_PENDING_LAPSE_AFTER",
	"SLACK_OAUTH_DISPLAY_NAME",
]);

/**
 * Self-hosting is the Helm chart, so an admin page that names a variable the application reads offers a setting.
 * Each such variable must be one that a workload of the chart sets: `variable: page, page` for each one that is not,
 * and `variable: listed, but no admin page offers it` for a stale entry of `listed`. It sees names in inline code
 * that an application YAML placeholder binds, not names in fenced code or relaxed Spring names.
 */
export function unsettable(
	sources: Sources,
	pages: ReadonlyMap<string, string>,
	listed: ReadonlySet<string> = OFFERED_WITHOUT_CHART_VALUE,
): string[] {
	const read = new Set(sources.bindings.map((binding) => binding.variable));
	const settable = requiredVariables(sources);
	const offered = new Map<string, string[]>();
	for (const [page, text] of pages) {
		for (const match of text.matchAll(/`(?<variable>[A-Z][A-Z0-9_]{3,})`/gu)) {
			const variable = match.groups?.variable ?? "";
			if (read.has(variable) && !settable.has(variable)) {
				offered.set(variable, [...new Set([...(offered.get(variable) ?? []), page])]);
			}
		}
	}
	return [
		...[...offered]
			.filter(([variable]) => !listed.has(variable))
			.map(([variable, where]) => `${variable}: ${where.join(", ")}`),
		...[...listed]
			.filter((variable) => !offered.has(variable))
			.map((variable) => `${variable}: listed, but no admin page offers it`),
	].toSorted();
}

async function adminPages(): Promise<Map<string, string>> {
	const entries = await readdir(path.join(REPO_ROOT, "docs/admin"), {
		recursive: true,
		withFileTypes: true,
	});
	const files = entries
		.filter((entry) => entry.isFile() && /\.mdx?$/u.test(entry.name))
		.map((entry) => path.relative(REPO_ROOT, path.join(entry.parentPath, entry.name)))
		// The generated reference lists every variable, also the ones without a chart value.
		.filter((file) => !file.includes("_configuration-reference"));
	return new Map(
		await Promise.all(
			files.map(
				async (file) => [file, await readFile(path.join(REPO_ROOT, file), "utf8")] as const,
			),
		),
	);
}

function report(title: string, variables: readonly string[], fix: string): void {
	if (variables.length > 0) {
		console.error(
			`${title}:\n${variables.map((variable) => `  ${variable}`).join("\n")}\n  ${fix}\n`,
		);
	}
}

if (import.meta.main) {
	const page = await readReference();
	const sources = await readSources();
	const { missing, stale, undescribed, flawed } = judge(requiredVariables(sources), page);
	const offered = unsettable(sources, await adminPages());
	report(
		"docs/admin offers variables that the chart cannot set",
		offered,
		"Add a chart value that sets the variable, or remove the variable from the operator docs.",
	);
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
			"additional-spring-configuration-metadata.json for a key without one, or the chart schema. " +
			"Then run `vp run generate:configuration-reference`.",
	);
	report(
		`${REFERENCE_PAGE} has descriptions that an operator cannot use`,
		flawed,
		"Rewrite the description at its source without a default, a Java identifier or a class name. " +
			"Then run `vp run generate:configuration-reference`.",
	);
	if (missing.length + stale.length + undescribed.length + flawed.length + offered.length > 0) {
		process.exit(1);
	}
	console.log(
		`check-configuration-reference: ${REFERENCE_PAGE} describes every variable the sources name.`,
	);
}
