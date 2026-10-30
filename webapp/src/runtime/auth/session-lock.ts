// A closed or stalled tab must not block another tab's renewal indefinitely.
const LOCK_WAIT_MS = 5000;

/**
 * Serializes cookie changes across tabs where browser locks are available. Without them, or after a
 * stalled tab holds the lock past the wait, the renewal runs anyway: the server accepts the
 * predecessor token for a short window after a rotation, so two renewals at once stay safe.
 */
export async function withSessionLock<T>(operation: () => Promise<T>): Promise<T> {
	// oxlint-disable-next-line typescript/no-unnecessary-condition -- Web Locks requires a secure context.
	if (navigator.locks === undefined) {
		return operation();
	}
	let started = false;
	try {
		return await navigator.locks.request(
			"hephaestus-session",
			{ signal: AbortSignal.timeout(LOCK_WAIT_MS) },
			async () => {
				started = true;
				return operation();
			},
		);
	} catch (error) {
		// oxlint-disable-next-line typescript/no-unnecessary-condition -- The awaited Web Locks callback sets this flag before running the operation.
		if (started) {
			throw error;
		}
		return operation();
	}
}
