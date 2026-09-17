/** Fire a container mutation without awaiting it, for reversible, no-confirm transitions. */
export function swallow(result: Promise<void> | void): void {
	Promise.resolve(result).catch(() => {
		/* the mutation's own onError already surfaced the failure */
	});
}
