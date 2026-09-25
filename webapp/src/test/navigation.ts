const realLocation = window.location;

/**
 * jsdom cannot perform a full page navigation, so `window.location` is replaced by one that records
 * every URL assigned to it. Pair with `restoreNavigation` in `afterEach`.
 */
export function captureNavigation(): string[] {
	const assigned: string[] = [];
	Object.defineProperty(window, "location", {
		configurable: true,
		value: {
			assign: (url: string) => {
				assigned.push(url);
			},
		},
	});
	return assigned;
}

export function restoreNavigation(): void {
	Object.defineProperty(window, "location", { configurable: true, value: realLocation });
}
