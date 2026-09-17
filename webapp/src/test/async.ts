export interface Deferred<T> {
	promise: Promise<T>;
	resolve: (value: T | PromiseLike<T>) => void;
	reject: (reason?: unknown) => void;
}

/**
 * A promise a test settles by hand — a request held open until the assertion that needs it in
 * flight has run. `Promise.withResolvers()` is the same thing, past `tsconfig.json`'s `lib`.
 */
export function deferred<T = void>(): Deferred<T> {
	let settle!: Deferred<T>["resolve"];
	let refuse!: Deferred<T>["reject"];
	// oxlint-disable-next-line promise/avoid-new -- the one place a promise is built from its callbacks.
	const promise = new Promise<T>((resolve, reject) => {
		settle = resolve;
		refuse = reject;
	});
	return { promise, resolve: settle, reject: refuse };
}

/** A promise that never settles: a request that never answers. */
export async function pending<T = never>(): Promise<T> {
	return deferred<T>().promise;
}

export async function sleep(ms: number): Promise<void> {
	const { promise, resolve } = deferred();
	setTimeout(resolve, ms);
	return promise;
}

export async function nextFrame(): Promise<void> {
	const { promise, resolve } = deferred();
	requestAnimationFrame(() => {
		resolve();
	});
	return promise;
}
