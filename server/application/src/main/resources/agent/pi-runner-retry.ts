import { setTimeout as delay } from "node:timers/promises";

export function retryDelayMs(attempt: number, baseMs = 1000): number {
	if (!Number.isInteger(attempt) || attempt < 1) {
		throw new Error(`attempt must be a positive integer, got: ${attempt}`);
	}
	if (!Number.isFinite(baseMs) || baseMs <= 0) {
		throw new Error(`baseMs must be a positive number, got: ${baseMs}`);
	}
	return baseMs * 2 ** (attempt - 1);
}

/** Retry throttling and server errors, not client errors. */
export function isRetryableStatus(status: number): boolean {
	return status >= 500 || status === 429;
}

/** Admission timeouts are not retried because the server may still be verifying the request. */
export function isTimeoutAbort(error: unknown): boolean {
	return (
		typeof error === "object" && error !== null && "name" in error && error.name === "TimeoutError"
	);
}

export interface RetryPolicy {
	readonly attempts: number;
	readonly baseMs?: number;
	readonly sleep?: (ms: number) => Promise<void>;
}

/** Retry only failures accepted by isWorthRetrying, up to the configured attempt limit. */
export async function retrying<T>(
	call: () => Promise<T>,
	isWorthRetrying: (error: unknown) => boolean,
	policy: RetryPolicy,
	onRetry?: (attempt: number, error: unknown, delayMs: number) => void,
): Promise<T> {
	if (!Number.isInteger(policy.attempts) || policy.attempts < 1) {
		throw new Error(`attempts must be a positive integer, got: ${policy.attempts}`);
	}
	const sleep = policy.sleep ?? delay;
	for (let attempt = 1; attempt < policy.attempts; attempt += 1) {
		try {
			return await call();
		} catch (error) {
			if (!isWorthRetrying(error)) {
				throw error;
			}
			const delayMs = retryDelayMs(attempt, policy.baseMs);
			onRetry?.(attempt, error, delayMs);
			await sleep(delayMs);
		}
	}
	return call();
}
