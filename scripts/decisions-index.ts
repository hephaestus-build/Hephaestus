/**
 * Prints the ADR index from the ADR files: each number, title and status. docs/decisions/README.md
 * § Index. The files are the index, so two pull requests that each add an ADR change no shared line.
 */
import { readdir, readFile } from "node:fs/promises";

export interface Decision {
	readonly file: string;
	readonly number: string;
	readonly title: string;
	readonly status: string;
}

/** The template is the shape an ADR is copied from, not a decision. */
export const isDecisionFile = (entry: string): boolean =>
	/^\d{4}-.+\.md$/u.test(entry) && entry !== "0000-template.md";

export async function readDecisions(directory = "docs/decisions"): Promise<Decision[]> {
	const entries = await readdir(directory);
	const files = entries.filter((entry) => isDecisionFile(entry)).toSorted();
	return Promise.all(
		files.map(async (file) => {
			const content = await readFile(`${directory}/${file}`, "utf8");
			// The header is everything above the first `## ` section.
			const [header = ""] = content.split(/^## /mu, 1);
			return {
				file,
				number: file.slice(0, 4),
				title: /^# ADR \d{4}: (?<title>.+)$/mu.exec(header)?.groups?.title ?? "",
				status: /^\*\*Status:\*\* (?<status>.+)$/mu.exec(header)?.groups?.status ?? "",
			};
		}),
	);
}

export const indexTable = (decisions: readonly Decision[]): string =>
	[
		"| # | Title | Status |",
		"| --- | --- | --- |",
		...decisions.map(
			({ file, number, title, status }) => `| [${number}](${file}) | ${title} | ${status} |`,
		),
	].join("\n");

if (import.meta.main) {
	console.log(indexTable(await readDecisions()));
}
