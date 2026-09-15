/**
 * Repeating a request whose answer the caller cannot afford to lose, with a doubling wait between
 * attempts. Which failures are worth repeating is the caller's to say; why the admission is one of
 * them is at that call site.
 */

/** The wait before attempt n: `baseMs`, then doubling, so a short outage is ridden out without a long stall. */
export function retryDelayMs(attempt: number, baseMs = 1_000): number {
	if (!Number.isInteger(attempt) || attempt < 1) {
		throw new Error(`attempt must be a positive integer, got: ${attempt}`);
	}
	if (!Number.isFinite(baseMs) || baseMs <= 0) {
		throw new Error(`baseMs must be a positive number, got: ${baseMs}`);
	}
	return baseMs * 2 ** (attempt - 1);
}

/**
 * Whether an HTTP answer is one a later attempt could answer differently. A 5xx or a 429 is the
 * server saying not now; every other status is the server having decided, and asking again only puts
 * the same question.
 */
export function isRetryableStatus(status: number): boolean {
	return status >= 500 || status === 429;
}

/**
 * Whether a failed `fetch` was ended by its own `AbortSignal.timeout` rather than by the transport.
 * A reset connection or a refused socket clears in place and is worth asking again; an attempt that
 * ran out its own clock is an answer still being computed, and asking again only queues the same
 * work behind it.
 */
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

/**
 * Runs `call` until it returns without throwing, or until the attempts are spent.
 *
 * @param isWorthRetrying decides from the thrown value whether another attempt could answer
 *   differently; an answer that has already been decided is not worth asking for twice
 * @param onRetry reports each attempt that is about to be repeated, so a run says why it took longer
 */
export async function retrying<T>(
	call: () => Promise<T>,
	isWorthRetrying: (error: unknown) => boolean,
	policy: RetryPolicy,
	onRetry: (attempt: number, error: unknown, delayMs: number) => void = () => {},
): Promise<T> {
	if (!Number.isInteger(policy.attempts) || policy.attempts < 1) {
		throw new Error(`attempts must be a positive integer, got: ${policy.attempts}`);
	}
	const sleep =
		policy.sleep ??
		((ms: number) =>
			new Promise<void>((resolve) => {
				setTimeout(resolve, ms);
			}));
	for (let attempt = 1; attempt < policy.attempts; attempt++) {
		try {
			return await call();
		} catch (error) {
			if (!isWorthRetrying(error)) throw error;
			const delayMs = retryDelayMs(attempt, policy.baseMs);
			onRetry(attempt, error, delayMs);
			await sleep(delayMs);
		}
	}
	// The last attempt is outside the loop: nothing follows it, so its failure is simply the caller's.
	return await call();
}
