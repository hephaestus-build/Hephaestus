export interface Deferred<T> {
	promise: Promise<T>;
	resolve: (value: T | PromiseLike<T>) => void;
	reject: (reason?: unknown) => void;
}

/**
 * A promise a test settles by hand — a request held open until the assertion that needs it in
 * flight has run. `Promise.withResolvers()` is the same thing in ES2024, which `tsconfig.json`'s
 * `lib` does not reach because it follows the browser target.
 */
export function deferred<T>(): Deferred<T> {
	let settle: Pick<Deferred<T>, "resolve" | "reject"> | undefined;
	// oxlint-disable-next-line promise/avoid-new -- the one place a promise is built from its callbacks.
	const promise = new Promise<T>((resolve, reject) => {
		settle = { resolve, reject };
	});
	// The executor has already run — it is synchronous by specification — but nothing tells the
	// type checker so.
	if (settle === undefined) {
		throw new Error("The promise executor did not run");
	}
	return { promise, ...settle };
}

/** A promise that never settles: a request that never answers. */
export async function pending<T = never>(): Promise<T> {
	return deferred<T>().promise;
}

export async function sleep(ms: number): Promise<void> {
	const { promise, resolve } = deferred<undefined>();
	setTimeout(resolve, ms);
	return promise;
}

export async function nextFrame(): Promise<void> {
	const { promise, resolve } = deferred<undefined>();
	requestAnimationFrame(() => {
		resolve(undefined);
	});
	return promise;
}
