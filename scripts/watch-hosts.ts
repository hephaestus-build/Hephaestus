import { setTimeout as sleep } from "node:timers/promises";

import {
	channelChangedAt,
	channelCommits,
	freezeAlertHours,
	heldHours,
} from "./automatic-promotion.ts";
import { requiredEnv } from "./lib/env.ts";
import { asRecord, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { parseChannel, type Channel } from "./reconcile-deployment.ts";

/**
 * Release / Host watch: checks from outside each host what a user and continuous staging depend on.
 * A failed check fails the run, and CI / Scheduled failures notifies the maintainers. The hosts'
 * own Alertmanager reaches no one unless its site configuration names a receiver.
 */

export type Fetch = (url: string, init: { signal: AbortSignal }) => Promise<{ status: number }>;

/** The API answers when one of `attempts` readiness requests, `pause` ms apart, returns 200. */
export async function apiAnswers(
	host: string,
	fetch: Fetch,
	attempts = 3,
	pause = 20_000,
): Promise<{ ok: true } | { ok: false; last: string }> {
	let last = "";
	for (let attempt = 1; attempt <= attempts; attempt += 1) {
		try {
			const response = await fetch(`https://${host}/api/readyz`, {
				signal: AbortSignal.timeout(10_000),
			});
			if (response.status === 200) {
				return { ok: true };
			}
			last = `HTTP ${response.status}`;
		} catch (error) {
			last = error instanceof Error ? error.message : String(error);
		}
		if (attempt < attempts) {
			await sleep(pause);
		}
	}
	return { ok: false, last };
}

/** A hold alerts once it is older than freezeAlertHours, or when deploy-state cannot date it. */
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
			alert: true,
			text: `Staging is frozen on ${channel.release}, and deploy-state does not say since when.`,
		};
	}
	return {
		alert: hours >= freezeAlertHours,
		text: `Staging is frozen on ${channel.release} since ${since.toISOString()} (${hours} h). Continuous staging deploys nothing until a promotion without freeze.`,
	};
}

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
					? `${host}: the API is ready`
					: `::error title=${host} API down::https://${host}/api/readyz did not answer 200 in 3 tries over 40 s (last: ${result.last}).`,
			);
		}
		process.exitCode = results.every(({ result }) => result.ok) ? 0 : 1;
	} else if (check === "freeze") {
		const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
		const file = "channels/staging.json";
		const contents = asRecord(
			parseJson(
				await output("gh", ["api", `repos/${repository}/contents/${file}?ref=deploy-state`]),
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
		console.log(alert ? `::error title=Staging frozen too long::${text}` : text);
		process.exitCode = alert ? 1 : 0;
	} else {
		throw new Error("usage: watch-hosts.ts api|freeze");
	}
}
