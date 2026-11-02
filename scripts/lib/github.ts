import { setTimeout as delay } from "node:timers/promises";

import { asRecord, asString, isRecord, parseJson } from "./json.ts";
import { output } from "./process.ts";

/** The waits before each new attempt. GitHub asks for at least a minute after a secondary rate limit. */
const RETRY_DELAYS_MS = [10_000, 30_000, 60_000];

/** A failed HTTP request that this repository made itself. `detail` holds the status and the answer. */
export class GitHubFailureError extends Error {
	readonly detail: string;

	constructor(message: string, detail: string) {
		super(message);
		this.name = "GitHubFailureError";
		this.detail = detail;
	}
}

/** HTTP 502, 503 or 504, or a secondary rate limit. `gh` names them on stderr and has no retry of its own. */
export function isTransientGitHubFailure(error: unknown): boolean {
	const transient = /\bHTTP 50[234]\b|secondary rate limit/iu;
	if (error instanceof GitHubFailureError) {
		return transient.test(error.detail);
	}
	return isRecord(error) && typeof error.stderr === "string" && transient.test(error.stderr);
}

/**
 * Repeats a request after a transient GitHub failure, a bounded number of times. Any other failure
 * stops at once. A 502 can follow a write that succeeded, so a write must be safe to repeat.
 */
export async function retryTransient<T>(
	request: () => Promise<T>,
	wait: (milliseconds: number) => Promise<unknown> = delay,
): Promise<T> {
	for (const milliseconds of RETRY_DELAYS_MS) {
		try {
			return await request();
		} catch (error) {
			if (!isTransientGitHubFailure(error)) {
				throw error;
			}
			console.warn(`GitHub failed transiently; the next attempt is in ${milliseconds / 1000} s`);
			await wait(milliseconds);
		}
	}
	return request();
}

/** Where `head` stands relative to `base` on GitHub: identical, ahead, behind or diverged. */
export async function compareStatus(
	repository: string,
	base: string,
	head: string,
): Promise<string> {
	return asString(
		asRecord(
			parseJson(await output("gh", ["api", `repos/${repository}/compare/${base}...${head}`])),
			"comparison",
		).status,
		"comparison status",
	);
}

/** A REST `POST` with a JSON body; `gh` supplies the token and the API host. */
export async function githubPost(path: string, body: object): Promise<unknown> {
	return parseJson(
		await output("gh", ["api", "--method", "POST", path, "--input", "-"], {
			input: JSON.stringify(body),
		}),
	);
}
