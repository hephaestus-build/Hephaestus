/**
 * Opens an issue for each new OpenJDK Vulnerability Group advisory, and says whether it affects the JRE
 * that the server image pins.
 *
 * The group publishes on the quarterly update dates and out of band, at
 * https://openjdk.org/groups/vulnerability/advisories/. Its advisory names the affected builds hours or
 * days before NVD gives the CVEs a CPE that a scanner can match, and before a vendor ships a fixed build.
 * On 2026-11-17 it named 25.0.5.0.3, the pinned build, before anyone here knew.
 *
 * An advisory opens an issue when it affects the pinned build, or names no build of its line. The issue
 * title carries the advisory date, so an advisory opens one issue, even after someone closes it. Only
 * advisories of the last 30 days count; older history stays quiet.
 */
import { appendFileSync, readFileSync } from "node:fs";

import { isSet, requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, parseJson, readJsonFileSync } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";

export const ADVISORIES = "https://openjdk.org/groups/vulnerability/advisories/";
const PAGE_LIMIT_BYTES = 2 * 1024 * 1024;
const RECENT_DAYS = 30;
export const ISSUE_AUTHOR = "app/github-actions";

/** The advisory dates that the index links to, newest first. */
export function advisoryDates(index: string): string[] {
	const dates = new Set(
		[...index.matchAll(/href="(?<date>\d{4}-\d{2}-\d{2})\/?"/gu)].flatMap(
			(match) => match.groups?.date ?? [],
		),
	);
	return [...dates].toSorted().toReversed();
}

export interface Advisory {
	/** The affected builds, one per line, as the advisory spells them (`25.0.5.0.3`, `8u512`). */
	readonly affected: readonly string[];
	readonly cves: readonly { readonly id: string; readonly cvss?: string }[];
}

const text = (html: string): string =>
	html
		.replaceAll(/<[^>]*>/gu, " ")
		.replaceAll(/&#?\w+;/gu, " ")
		.replaceAll(/\s+/gu, " ");

/** The affected builds and the CVEs of one advisory page. A page without them throws. */
export function parseAdvisory(html: string): Advisory {
	const body = text(html);
	const sentence = /affected versions are (?<list>.+?),? and earlier/u.exec(body)?.groups?.list;
	if (sentence === undefined) {
		throw new Error("the advisory names no affected versions");
	}
	const affected = sentence
		.split(/,|\band\b/u)
		.map((version) => version.trim())
		.filter((version) => /^\d+(?:[.u]\d+)*$/u.test(version));
	if (affected.length === 0) {
		throw new Error(`the advisory's affected versions are unreadable: ${sentence}`);
	}
	// The OpenJFX matrix follows the OpenJDK one; its CVEs are not in the JRE.
	const openjdk = body.split("OpenJFX Risk matrix")[0] ?? body;
	const cves = new Map<string, string | undefined>();
	for (const match of openjdk.matchAll(/(?<id>CVE-\d{4}-\d{4,})\D*?(?<cvss>\d{1,2}\.\d)\s/gu)) {
		const id = match.groups?.id;
		if (id !== undefined && !cves.has(id)) {
			cves.set(id, match.groups?.cvss);
		}
	}
	return {
		affected,
		cves: [...cves].map(([id, cvss]) => (cvss === undefined ? { id } : { id, cvss })),
	};
}

const parts = (version: string): number[] =>
	(version.split(/[_+-]/u)[0] ?? "").split(".").map(Number);

/**
 * Whether a pinned build such as `25.0.5.0.3_5` is at or below the affected build of its line, or
 * `undefined` when the advisory names no build of that line.
 */
export function pinAffected(pinned: string, affected: readonly string[]): boolean | undefined {
	const pin = parts(pinned);
	const same = affected.map(parts).find((candidate) => candidate[0] === pin[0]);
	if (same === undefined) {
		return undefined;
	}
	for (let index = 0; index < Math.max(pin.length, same.length); index += 1) {
		const left = pin[index] ?? 0;
		const right = same[index] ?? 0;
		if (left !== right) {
			return left < right;
		}
	}
	return true;
}

export function issueTitle(date: string): string {
	return `OpenJDK vulnerability advisory ${date}`;
}

export function issueBody(date: string, advisory: Advisory, pinned: string): string {
	const affected = pinAffected(pinned, advisory.affected);
	let verdict = `The pinned JRE ${pinned} is newer than the affected build of its line.`;
	if (affected === undefined) {
		verdict = `The advisory names no build of the pinned JRE's line (${pinned}). Check its risk matrix.`;
	} else if (affected) {
		verdict = `**The pinned JRE ${pinned} is affected.** Mitigate now, and pin a fixed build as soon as a buildpack ships one.`;
	}
	return [
		"@hephaestus-build/maintainer: the OpenJDK Vulnerability Group published an advisory.",
		"",
		`Advisory: ${ADVISORIES}${date}`,
		`Affected builds: ${advisory.affected.join(", ")}, and earlier.`,
		"",
		verdict,
		"",
		"| CVE | CVSS 3.1 |",
		"| --- | --- |",
		...advisory.cves.map((cve) => `| ${cve.id} | ${cve.cvss ?? ""} |`),
		"",
		"The pin is `.github/ci-tools/buildpack-liberica-jre.json`. NVD often gives these CVEs a CPE days later, so image scans may not report them yet.",
		"Response: docs/contributor/vulnerability-remediation.mdx § Java runtime.",
		"",
		"Opened by `.github/workflows/jvm-watch.yml`. The title carries the advisory date, so the watcher never opens a second issue for it.",
		"",
	].join("\n");
}

/** The advisories that need an issue: recent, and without an issue of their title. */
export function advisoriesToOpen(
	dates: readonly string[],
	titles: ReadonlySet<string>,
	now: Date,
): string[] {
	const floor = now.getTime() - RECENT_DAYS * 24 * 60 * 60 * 1000;
	return dates.filter(
		(date) => Date.parse(`${date}T00:00:00Z`) >= floor && !titles.has(issueTitle(date)),
	);
}

async function fetchPage(url: string): Promise<string> {
	const response = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(30_000) });
	if (response.status !== 200 || response.body === null) {
		throw new Error(`${url} answered ${response.status}`);
	}
	const chunks: Uint8Array[] = [];
	let size = 0;
	for await (const chunk of response.body) {
		size += chunk.byteLength;
		if (size > PAGE_LIMIT_BYTES) {
			throw new Error(`${url} exceeds ${PAGE_LIMIT_BYTES} bytes`);
		}
		chunks.push(chunk);
	}
	return Buffer.concat(chunks).toString("utf8");
}

if (import.meta.main) {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const pinned = asString(
		asRecord(readJsonFileSync(".github/ci-tools/buildpack-liberica-jre.json"), "JRE pin").version,
		"JRE pin version",
	);
	const line = readFileSync(".java-version", "utf8").trim();
	if (pinned.split(".")[0] !== line) {
		throw new Error(`the JRE pin ${pinned} is not of the .java-version line ${line}`);
	}
	// Closed issues count: an advisory that someone triaged and closed stays closed.
	const titles = new Set(
		asArray(
			parseJson(
				await output("gh", [
					"issue",
					"list",
					"--repo",
					repository,
					"--state",
					"all",
					"--limit",
					"200",
					"--json",
					"author,title",
				]),
			),
			"gh issue list",
		)
			.map((value) => asRecord(value, "issue"))
			.filter((issue) => asRecord(issue.author, "issue author").login === ISSUE_AUTHOR)
			.map((issue) => asString(issue.title, "issue title")),
	);
	const due = advisoriesToOpen(advisoryDates(await fetchPage(ADVISORIES)), titles, new Date());
	let opened = 0;
	for (const date of due) {
		const advisory = parseAdvisory(await fetchPage(`${ADVISORIES}${date}`));
		// The pinned build is newer than what the advisory affects: nothing to act on.
		if (pinAffected(pinned, advisory.affected) === false) {
			continue;
		}
		opened += 1;
		const create = async (labels: readonly string[]) =>
			run("gh", [
				"issue",
				"create",
				"--repo",
				repository,
				"--title",
				issueTitle(date),
				...labels.flatMap((label) => ["--label", label]),
				"--body",
				issueBody(date, advisory, pinned),
			]);
		// A deleted label must not silence the advisory.
		await create(["security"]).catch(async () => create([]));
		process.stdout.write(`Opened an issue for the advisory of ${date}.\n`);
	}
	if (opened === 0) {
		process.stdout.write("No new OpenJDK advisory affects the pinned JRE.\n");
	}
	const summaryPath = process.env.GITHUB_STEP_SUMMARY;
	if (isSet(summaryPath)) {
		appendFileSync(
			summaryPath,
			`### OpenJDK advisories\n\n${due.length} recent without an issue, ${opened} affecting the pinned JRE ${pinned}\n`,
		);
	}
}
