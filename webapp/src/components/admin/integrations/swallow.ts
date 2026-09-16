/**
 * Fire a route-handler/container mutation without awaiting it (reversible, no-confirm transitions)
 * while swallowing rejections — the mutation's own `onError` already surfaced a toast, so leaving the
 * promise unhandled here would otherwise reject into the void.
 */
export function swallow(result: Promise<void> | void): void {
	Promise.resolve(result).catch(() => {
		/* the mutation's own onError already surfaced the failure */
	});
}
