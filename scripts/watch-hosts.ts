import { appendFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";

import {
	channelChangedAt,
	channelCommits,
	freezeAlertHours,
	heldHours,
} from "./automatic-promotion.ts";
import { requiredEnv } from "./lib/env.ts";
import { asRecord, isRecord, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { parseChannel, type Channel } from "./reconcile-deployment.ts";

/**
 * Release / Host watch: checks from outside each host what a user and continuous staging depend on.
 * Each host's server and the Staging freeze keep one issue each: the first failing verdict opens
 * it and notifies the maintainers, and the next passing verdict closes it. The hosts' own Alertmanager reaches no one unless its site configuration names a
 * receiver.
 */

export type Fetch = (
	url: string,
	init: { signal: AbortSignal; redirect: "manual" },
) => Promise<{ status: number; text: () => Promise<string> }>;

/** Whether a readiness body is the server's own `{"status":"UP"}`, not a page that a proxy served. */
function serverUp(body: string): boolean {
	try {
		const parsed: unknown = JSON.parse(body);
		return isRecord(parsed) && parsed.status === "UP";
	} catch {
		return false;
	}
}

/**
 * The server is ready when one of `attempts` requests to its public readiness path, `pause` ms apart,
 * returns 200 with its health body. A web app in front of a stopped server also answers 200, with HTML.
 */
export async function apiAnswers(
	host: string,
	fetch: Fetch,
	attempts = 3,
	pause = 20_000,
): Promise<{ ok: true } | { ok: false; last: string }> {
	let last = "";
	for (let attempt = 1; attempt <= attempts; attempt += 1) {
		try {
			// A redirect leads to a page, never to the server's readiness.
			const response = await fetch(`https://${host}/readyz`, {
				signal: AbortSignal.timeout(10_000),
				redirect: "manual",
			});
			const body = await response.text();
			if (response.status === 200 && serverUp(body)) {
				return { ok: true };
			}
			last = `HTTP ${response.status}${response.status === 200 ? " without the server's health body" : ""}`;
		} catch (error) {
			last = error instanceof Error ? error.message : String(error);
		}
		if (attempt < attempts) {
			await sleep(pause);
		}
	}
	return { ok: false, last };
}

/** What the freeze check concluded. Only `frozen` alerts; `undated` leaves an open issue as it is. */
export type FreezeVerdict = "following" | "held" | "frozen" | "undated";

/**
 * A hold alerts once it is older than freezeAlertHours. Without a date from deploy-state it only
 * warns: continuous staging already shows every held run as a warning.
 */
export function freezeVerdict(
	channel: Channel,
	since: Date | undefined,
	now: Date,
): { verdict: FreezeVerdict; text: string } {
	if (channel.freeze !== true) {
		return { verdict: "following", text: `Staging follows main; it is on ${channel.release}.` };
	}
	const hours = heldHours(since, now);
	if (since === undefined || hours === undefined) {
		return {
			verdict: "undated",
			text: `Staging is frozen on ${channel.release}, and deploy-state did not say since when.`,
		};
	}
	return {
		verdict: hours >= freezeAlertHours ? "frozen" : "held",
		text: `Staging is frozen on ${channel.release} since ${since.toISOString()} (${hours} h). Continuous staging deploys nothing until a promotion without freeze.`,
	};
}

/** One problem that the watch tracks with its own issue: a host's server, or the Staging freeze. */
export interface Subject {
	key: string;
	title: string;
	text: string;
}

export const apiSubject = (host: string): Subject => ({
	key: `api:${host}`,
	title: `Host watch: the server of ${host} is not ready`,
	text: `the server of ${host} stopped reporting ready from outside.`,
});
export const freezeSubject: Subject = {
	key: "freeze",
	title: "Host watch: Staging has been frozen for more than a day",
	text: "the Staging channel has been frozen for 24 hours or more, so continuous staging deploys nothing.",
};
export const markerOf = (subject: Subject) => `<!-- hephaestus:host-watch:${subject.key} -->`;

type IssueAction =
	| { subject: Subject; kind: "open" }
	| { subject: Subject; kind: "close"; number: number };

/**
 * What the report does for each subject: open its issue on the first failing verdict, and close it
 * on the first passing one. A failing verdict with the issue open notifies no one again. A subject
 * with no conclusion, such as an undated freeze, leaves its issue as it is.
 */
export function issueActions(
	verdicts: { subject: Subject; failing: boolean | undefined }[],
	openIssue: (subject: Subject) => number | undefined,
): IssueAction[] {
	return verdicts.flatMap(({ subject, failing }): IssueAction[] => {
		const number = openIssue(subject);
		if (failing === true && number === undefined) {
			return [{ subject, kind: "open" }];
		}
		if (failing === false && number !== undefined) {
			return [{ subject, kind: "close", number }];
		}
		return [];
	});
}

/**
 * The verdicts that the check jobs passed on. A check that ended without one (it crashed, timed out
 * or was cancelled) concluded nothing about the hosts, so the report refuses: its own failure is
 * what CI / Scheduled failures reports.
 */
export function reportVerdicts(
	apiVerdicts: string,
	freeze: string,
): { subject: Subject; failing: boolean | undefined }[] {
	const parsed: unknown = apiVerdicts === "" ? undefined : JSON.parse(apiVerdicts);
	if (!isRecord(parsed) || Object.keys(parsed).length === 0) {
		throw new Error("The server check ended without a verdict for any host");
	}
	const hosts = Object.entries(parsed).map(([host, verdict]) => {
		if (verdict !== "ready" && verdict !== "down") {
			throw new Error(`The server check gave ${host} no verdict`);
		}
		return { subject: apiSubject(host), failing: verdict === "down" };
	});
	if (!isFreezeVerdict(freeze)) {
		throw new Error("The freeze check ended without a verdict");
	}
	return [...hosts, { subject: freezeSubject, failing: freezeFailing[freeze] }];
}

const freezeFailing: Record<FreezeVerdict, boolean | undefined> = {
	following: false,
	held: false,
	frozen: true,
	undated: undefined,
};
const isFreezeVerdict = (value: string): value is FreezeVerdict =>
	Object.hasOwn(freezeFailing, value);

/** One GitHub CLI call, bounded so that a stalled request cannot hold the run until its timeout. */
const gh = async (args: string[]) => output("gh", args, { signal: AbortSignal.timeout(60_000) });

/** A check's verdict for the report job: a step output, read through the job's outputs. */
async function writeOutput(name: string, value: string): Promise<void> {
	await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `${name}=${value}\n`);
}

// Each check ends successfully once it has a verdict: the report turns verdicts into issues, and
// the error annotations show a failing verdict in the run.
if (import.meta.main) {
	const check = process.argv[2];
	if (check === "api") {
		const hosts = requiredEnv(process.env, "HOSTS").split(/\s+/u).filter(Boolean);
		const results = await Promise.all(
			hosts.map(async (host) => ({ host, result: await apiAnswers(host, globalThis.fetch) })),
		);
		for (const { host, result } of results) {
			console.log(
				result.ok
					? `${host}: the server is ready`
					: `::error title=${host} server down::https://${host}/readyz did not report the server ready in 3 tries over 40 s (last: ${result.last}).`,
			);
		}
		await writeOutput(
			"verdicts",
			JSON.stringify(
				Object.fromEntries(results.map(({ host, result }) => [host, result.ok ? "ready" : "down"])),
			),
		);
	} else if (check === "freeze") {
		const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
		const file = "channels/staging.json";
		const contents = asRecord(
			parseJson(
				await output("gh", ["api", `repos/${repository}/contents/${file}?ref=deploy-state`], {
					signal: AbortSignal.timeout(30_000),
				}),
			),
			"channel contents",
		).content;
		if (typeof contents !== "string") {
			throw new Error(`deploy-state has no ${file}`);
		}
		const channel = parseChannel(parseJson(Buffer.from(contents, "base64").toString("utf8")));
		const since =
			channel.freeze === true
				? await channelChangedAt(channelCommits(repository, file))
				: undefined;
		const { verdict, text } = freezeVerdict(channel, since, new Date());
		console.log(
			verdict === "frozen" ? `::error title=Staging frozen too long::${text}` : `::notice::${text}`,
		);
		await writeOutput("verdict", verdict);
	} else if (check === "report") {
		const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
		const runUrl = requiredEnv(process.env, "RUN_URL");
		const verdicts = reportVerdicts(
			process.env.API_VERDICTS ?? "",
			process.env.FREEZE_VERDICT ?? "",
		);
		const listed = await gh([
			"api",
			"--paginate",
			`repos/${repository}/issues?state=open&creator=github-actions%5Bbot%5D&per_page=100`,
			"--jq",
			".[] | select(.pull_request == null) | {number, body}",
		]);
		const open = listed
			.split("\n")
			.filter((line) => line.trim() !== "")
			.map((line) => asRecord(parseJson(line), "issue"));
		const actions = issueActions(verdicts, (subject) => {
			const found = open.find(
				(issue) => typeof issue.body === "string" && issue.body.startsWith(markerOf(subject)),
			);
			return typeof found?.number === "number" ? found.number : undefined;
		});
		for (const action of actions) {
			if (action.kind === "open") {
				await gh([
					"issue",
					"create",
					"-R",
					repository,
					"--title",
					action.subject.title,
					"--body",
					`${markerOf(action.subject)}\n@hephaestus-build/maintainer: ${action.subject.text} First failed run: ${runUrl}. This issue closes itself when the check passes again. Response: docs/contributor/release-management.mdx § Host watch.`,
				]);
			} else {
				await gh([
					"issue",
					"close",
					String(action.number),
					"-R",
					repository,
					"--comment",
					`The check passed again: ${runUrl}.`,
				]);
			}
			console.log(`${action.kind} ${action.subject.key}`);
		}
	} else {
		throw new Error("usage: watch-hosts.ts api|freeze|report");
	}
}
