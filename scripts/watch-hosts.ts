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
 * Each check keeps one issue: the first failure opens it and notifies the maintainers, and the next
 * pass closes it. The hosts' own Alertmanager reaches no one unless its site configuration names a
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

/**
 * A hold alerts once it is older than freezeAlertHours. Without a date from deploy-state it only
 * warns: continuous staging already shows every held run as a warning.
 */
export function freezeVerdict(
	channel: Channel,
	since: Date | undefined,
	now: Date,
): { alert: boolean; text: string } {
	if (channel.freeze !== true) {
		return { alert: false, text: `Staging follows main; it is on ${channel.release}.` };
	}
	const hours = heldHours(since, now);
	if (since === undefined || hours === undefined) {
		return {
			alert: false,
			text: `Staging is frozen on ${channel.release}, and deploy-state did not say since when.`,
		};
	}
	return {
		alert: hours >= freezeAlertHours,
		text: `Staging is frozen on ${channel.release} since ${since.toISOString()} (${hours} h). Continuous staging deploys nothing until a promotion without freeze.`,
	};
}

type HostWatchCheck = "api" | "freeze";

/** The one issue each check keeps, found again by the marker that starts its body. */
export const hostWatchIssues = {
	api: {
		marker: "<!-- hephaestus:host-watch:api -->",
		title: "Host watch: a host's server is not ready",
		text: "the server of a host stopped reporting ready from outside.",
	},
	freeze: {
		marker: "<!-- hephaestus:host-watch:freeze -->",
		title: "Host watch: Staging has been frozen for more than a day",
		text: "the Staging channel has been frozen for 24 hours or more, so continuous staging deploys nothing.",
	},
} satisfies Record<HostWatchCheck, { marker: string; title: string; text: string }>;

/**
 * What the report does for each check: open its issue on the first failure, close it on the first
 * pass. A failure with the issue open notifies no one again, and a cancelled or skipped check
 * changes nothing.
 */
type IssueAction =
	| { check: HostWatchCheck; kind: "open" }
	| { check: HostWatchCheck; kind: "close"; number: number };

export function issueActions(
	results: Record<HostWatchCheck, string>,
	openIssue: (check: HostWatchCheck) => number | undefined,
): IssueAction[] {
	return (["api", "freeze"] as const).flatMap((check): IssueAction[] => {
		const number = openIssue(check);
		if (results[check] === "failure" && number === undefined) {
			return [{ check, kind: "open" }];
		}
		if (results[check] === "success" && number !== undefined) {
			return [{ check, kind: "close", number }];
		}
		return [];
	});
}

/** One GitHub CLI call, bounded so that a stalled request cannot hold the run until its timeout. */
const gh = async (args: string[]) => output("gh", args, { signal: AbortSignal.timeout(60_000) });

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
		process.exitCode = results.every(({ result }) => result.ok) ? 0 : 1;
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
		const { alert, text } = freezeVerdict(channel, since, new Date());
		console.log(alert ? `::error title=Staging frozen too long::${text}` : `::notice::${text}`);
		process.exitCode = alert ? 1 : 0;
	} else if (check === "report") {
		const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
		const runUrl = requiredEnv(process.env, "RUN_URL");
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
		const actions = issueActions(
			{
				api: requiredEnv(process.env, "API_RESULT"),
				freeze: requiredEnv(process.env, "FREEZE_RESULT"),
			},
			(name) => {
				const found = open.find(
					(issue) =>
						typeof issue.body === "string" && issue.body.startsWith(hostWatchIssues[name].marker),
				);
				return typeof found?.number === "number" ? found.number : undefined;
			},
		);
		for (const action of actions) {
			const issue = hostWatchIssues[action.check];
			if (action.kind === "open") {
				await gh([
					"issue",
					"create",
					"-R",
					repository,
					"--title",
					issue.title,
					"--body",
					`${issue.marker}\n@hephaestus-build/maintainer: ${issue.text} First failed run: ${runUrl}. This issue closes itself when the check passes again. Response: docs/contributor/release-management.mdx § Host watch.`,
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
			console.log(`${action.kind} ${action.check}`);
		}
	} else {
		throw new Error("usage: watch-hosts.ts api|freeze|report");
	}
}
