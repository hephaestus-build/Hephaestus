/**
 * Waits until an image tag that a release just moved resolves to the digest that it moved it to:
 * `node scripts/wait-for-image-tag.ts <repository:tag> <sha256:digest>`.
 *
 * A registry can serve the tag's old digest, or no tag at all, for a moment after the write. So the tag's answer is
 * never trusted as a source: it is only compared with the verified digest, and read again while it differs, after 1,
 * 2, 4 and 8 s and then every 15 s, for at most 60 s. Any other registry error fails at once. The manifest job of
 * `reusable-docker-build.yml` waits the same way in shell, because that job runs no repository code.
 */
import { spawnSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

import { isRecord, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const LIMIT_SECONDS = 60;

/** What the registry answers for the tag now: its digest, no such tag, or another error. */
export type TagAnswer = { digest: string } | { notFound: true } | { error: string };

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

/**
 * Reads the tag until it resolves to `digest`. Throws when another error answers, or when the time limit passes, with
 * the digest that the tag served last.
 */
export async function waitForTag(
	reference: string,
	digest: string,
	read: (reference: string) => TagAnswer,
	pause: (seconds: number) => Promise<void>,
	log: (line: string) => void,
): Promise<void> {
	const schedule = waits();
	let waited = 0;
	for (let attempt = 0; ; attempt += 1) {
		const answer = read(reference);
		if ("error" in answer) {
			throw new Error(`Cannot read ${reference}: ${answer.error}`);
		}
		if ("digest" in answer && answer.digest === digest) {
			return;
		}
		const seen = "digest" in answer ? answer.digest : "no such tag";
		const delay = schedule[attempt];
		if (delay === undefined) {
			throw new Error(
				`${reference} resolves to ${seen}, not ${digest}, ${String(waited)} s after the promotion`,
			);
		}
		log(
			`${reference} resolves to ${seen}, not ${digest}, ${String(waited)} s after the promotion; reading it again in ${String(delay)} s`,
		);
		await pause(delay);
		waited += delay;
	}
}

/** The registry's answer through `docker buildx imagetools inspect`. */
function readTag(reference: string): TagAnswer {
	const result = spawnSync(
		"docker",
		["buildx", "imagetools", "inspect", reference, "--format", "{{json .Manifest}}"],
		{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
	);
	if (result.status !== 0) {
		return /not found/u.test(result.stderr)
			? { notFound: true }
			: { error: result.stderr.trim() || `docker exited with ${String(result.status)}` };
	}
	const manifest: unknown = parseJson(result.stdout);
	return isRecord(manifest) && typeof manifest.digest === "string"
		? { digest: manifest.digest }
		: { error: "the registry's answer names no digest" };
}

if (import.meta.main) {
	const [reference, digest] = process.argv.slice(2);
	if (reference === undefined || digest === undefined || !/^sha256:[a-f0-9]{64}$/u.test(digest)) {
		throw new Error("Usage: wait-for-image-tag.ts <repository:tag> <sha256:digest>");
	}
	try {
		await waitForTag(
			reference,
			digest,
			readTag,
			async (seconds) => sleep(seconds * 1000),
			(line) => console.log(line),
		);
	} catch (error) {
		console.log(
			`::error title=Image alias::${error instanceof Error ? error.message : String(error)}`,
		);
		process.exit(1);
	}
	console.log(`${reference} resolves to ${digest}`);
}
