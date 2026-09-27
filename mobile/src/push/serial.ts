/**
 * Runs push registration work one task at a time, in the order asked. A person turning notifications
 * off while an automatic token refresh is on its way waits for that refresh to land, then removes the
 * registration — so the explicit choice is always the last word, never overwritten by a late refresh.
 */
export function createSerialQueue(): <T>(task: () => Promise<T>) => Promise<T> {
	let tail: Promise<void> = Promise.resolve();
	return async (task) => {
		const previous = tail;
		let release: (() => void) | undefined;
		// oxlint-disable-next-line promise/avoid-new -- the queue needs a promise it resolves itself
		tail = new Promise<void>((resolve) => {
			release = resolve;
		});
		await previous;
		try {
			return await task();
		} finally {
			release?.();
		}
	};
}
