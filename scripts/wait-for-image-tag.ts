/**
 * Checks the image tags that a release moves, under the promotion lock:
 * - `node scripts/wait-for-image-tag.ts current <repository:tag>` prints the tag's digest before the move, or `none`;
 * - `node scripts/wait-for-image-tag.ts wait <repository:tag> <sha256:promoted> <sha256:previous|none>` waits until
 *   the tag resolves to the promoted digest.
 *
 * A registry can serve the tag's previous digest, or no tag at all, for a moment after the write. Only those two
 * answers are lag: the check reads the tag again after 1, 2, 4 and 8 s and then every 15 s, for at most 60 s. Any third
 * digest means that another writer moved the tag, and the check fails at once. A rate limit or a server error is read
 * again within the same 60 s; any other registry error fails at once. The tag's answer is never a source, only compared.
 * The manifest job of `reusable-docker-build.yml` waits in shell, because that job runs no repository code.
 */
import { spawnSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

import { isRecord, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const LIMIT_SECONDS = 60;
const DIGEST = /^sha256:[a-f0-9]{64}$/u;

/** What the registry answers for the tag now. `transient` is a rate limit or a server error. */
export type TagAnswer =
	| { digest: string }
	| { notFound: true }
	| { transient: string }
	| { error: string };

/** A tag that resolves to neither its digest before the promotion nor the promoted one. */
export class AliasChangedError extends Error {
	constructor(message: string) {
		super(message);
		this.name = "AliasChangedError";
	}
}

/** The waits, in seconds, before each read after the first, within the time limit. */
export function waits(limit = LIMIT_SECONDS): number[] {
	const schedule: number[] = [];
	let waited = 0;
	for (let delay = 1; waited + delay <= limit; delay = Math.min(delay * 2, 15)) {
		schedule.push(delay);
		waited += delay;
	}
	return schedule;
}

interface Registry {
	read: (reference: string) => TagAnswer;
	pause: (seconds: number) => Promise<void>;
	log: (line: string) => void;
}

/**
 * Reads `reference` until `settled` returns a result, waiting between reads while `pending` names why. Throws at the
 * time limit with the last reason.
 */
async function readUntil<T>(
	reference: string,
	registry: Registry,
	settled: (answer: TagAnswer) => { done: T } | { pending: string },
): Promise<T> {
	const schedule = waits();
	let waited = 0;
	for (let attempt = 0; ; attempt += 1) {
		const answer = registry.read(reference);
		if ("error" in answer) {
			throw new Error(`Cannot read ${reference}: ${answer.error}`);
		}
		const result = "transient" in answer ? { pending: answer.transient } : settled(answer);
		if ("done" in result) {
			return result.done;
		}
		const delay = schedule[attempt];
		if (delay === undefined) {
			throw new Error(`${reference}: ${result.pending}, ${String(waited)} s after the first read`);
		}
		registry.log(
			`${reference}: ${result.pending}, ${String(waited)} s after the first read; reading it again in ${String(delay)} s`,
		);
		await registry.pause(delay);
		waited += delay;
	}
}

/** The tag's digest now, or `null` when there is no such tag. */
export async function currentDigest(reference: string, registry: Registry): Promise<string | null> {
	return readUntil(reference, registry, (answer) => ({
		done: "digest" in answer ? answer.digest : null,
	}));
}

/**
 * Reads the tag until it resolves to `promoted`. It waits only while the tag answers no tag or `previous`, its digest
 * before the promotion; any other digest throws `AliasChangedError` at once.
 */
export async function waitForTag(
	reference: string,
	promoted: string,
	previous: string | null,
	registry: Registry,
): Promise<void> {
	await readUntil(reference, registry, (answer) => {
		if ("digest" in answer && answer.digest === promoted) {
			return { done: undefined };
		}
		if ("digest" in answer && answer.digest !== previous) {
			throw new AliasChangedError(
				`${reference} resolves to ${answer.digest}, which is neither its digest before the promotion (${previous ?? "no tag"}) nor the promoted ${promoted}`,
			);
		}
		const seen = "digest" in answer ? `still ${answer.digest}` : "no such tag";
		return { pending: `${seen}, not the promoted ${promoted}` };
	});
}

/** The registry's answer through `docker buildx imagetools inspect`. */
function readTag(reference: string): TagAnswer {
	const result = spawnSync(
		"docker",
		["buildx", "imagetools", "inspect", reference, "--format", "{{json .Manifest}}"],
		{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
	);
	if (result.status !== 0) {
		const message = result.stderr.trim() || `docker exited with ${String(result.status)}`;
		if (/not found/iu.test(message)) {
			return { notFound: true };
		}
		return /\b(?:429|5\d\d)\b|too many requests|bad gateway|service unavailable|gateway time-?out|internal server error/iu.test(
			message,
		)
			? { transient: message }
			: { error: message };
	}
	const manifest: unknown = parseJson(result.stdout);
	return isRecord(manifest) && typeof manifest.digest === "string"
		? { digest: manifest.digest }
		: { error: "the registry's answer names no digest" };
}

if (import.meta.main) {
	const [mode, reference, promoted, previous] = process.argv.slice(2);
	const registry: Registry = {
		read: readTag,
		pause: async (seconds) => sleep(seconds * 1000),
		log: (line) => console.log(line),
	};
	try {
		if (mode === "current" && reference !== undefined) {
			console.log((await currentDigest(reference, registry)) ?? "none");
		} else if (
			mode === "wait" &&
			reference !== undefined &&
			promoted !== undefined &&
			DIGEST.test(promoted) &&
			previous !== undefined &&
			(previous === "none" || DIGEST.test(previous))
		) {
			await waitForTag(reference, promoted, previous === "none" ? null : previous, registry);
			console.log(`${reference} resolves to ${promoted}`);
		} else {
			throw new Error(
				"Usage: wait-for-image-tag.ts current <repository:tag> | wait <repository:tag> <sha256:promoted> <sha256:previous|none>",
			);
		}
	} catch (error) {
		const title =
			error instanceof AliasChangedError ? "Image alias changed by another writer" : "Image alias";
		console.log(
			`::error title=${title}::${error instanceof Error ? error.message : String(error)}`,
		);
		process.exit(1);
	}
}
