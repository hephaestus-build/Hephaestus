/** Serialize cookie changes across tabs so late responses cannot overwrite a newer session. */
export function withSessionLock<T>(operation: () => Promise<T>): Promise<T> {
	// oxlint-disable-next-line typescript/no-unnecessary-condition -- Web Locks requires a secure context.
	return navigator.locks ? navigator.locks.request("hephaestus-session", operation) : operation();
}
