let database: Promise<IDBDatabase> | undefined;

function lockDatabase(): Promise<IDBDatabase> {
	database ??= new Promise((resolve, reject) => {
		const request = indexedDB.open("hephaestus-session-lock", 1);
		request.onupgradeneeded = () => request.result.createObjectStore("session");
		request.onerror = () => reject(request.error);
		request.onblocked = () => reject(new Error("Session lock database is blocked"));
		request.onsuccess = () => {
			request.result.onversionchange = () => {
				request.result.close();
				database = undefined;
			};
			resolve(request.result);
		};
	}).catch((error: unknown) => {
		database = undefined;
		throw error;
	});
	return database;
}

async function withDatabaseLock<T>(operation: () => Promise<T>): Promise<T> {
	const db = await lockDatabase();
	return new Promise<T>((resolve, reject) => {
		const transaction = db.transaction("session", "readwrite");
		const store = transaction.objectStore("session");
		let started = false;
		let outcome: { value: T } | { error: unknown } | undefined;
		transaction.onabort = () => reject(transaction.error ?? new Error("Session lock was aborted"));
		transaction.onerror = () => reject(transaction.error);
		transaction.oncomplete = () => {
			if (outcome === undefined) {
				reject(new Error("Session lock ended before the operation"));
			} else if ("error" in outcome) {
				reject(outcome.error);
			} else {
				resolve(outcome.value);
			}
		};
		// A queued read keeps the write transaction open across async cookie changes. The browser
		// serializes these transactions across tabs and releases them when a tab closes.
		const hold = () => {
			const read = store.get(0);
			read.onsuccess = () => {
				if (!started) {
					started = true;
					void Promise.resolve()
						.then(operation)
						.then(
							(value) => {
								outcome = { value };
							},
							(error: unknown) => {
								outcome = { error };
							},
						);
				}
				if (outcome === undefined) hold();
			};
		};
		hold();
	});
}

/** Serialize cookie changes across tabs so late responses cannot overwrite a newer session. */
export async function withSessionLock<T>(operation: () => Promise<T>): Promise<T> {
	// oxlint-disable-next-line typescript/no-unnecessary-condition -- Web Locks requires a secure context.
	return navigator.locks === undefined
		? withDatabaseLock(operation)
		: navigator.locks.request("hephaestus-session", operation);
}
