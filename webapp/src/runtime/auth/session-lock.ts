let database: Promise<IDBDatabase> | undefined;

async function lockDatabase(): Promise<IDBDatabase> {
	try {
		// oxlint-disable-next-line promise/avoid-new -- IndexedDB exposes events, not promises.
		database ??= new Promise<IDBDatabase>((resolve, reject) => {
			const request = indexedDB.open("hephaestus-session-lock", 1);
			request.addEventListener(
				"upgradeneeded",
				() => {
					request.result.createObjectStore("session");
				},
				{ once: true },
			);
			request.addEventListener(
				"error",
				() => reject(request.error ?? new Error("Session lock database failed")),
				{ once: true },
			);
			request.addEventListener(
				"blocked",
				() => reject(new Error("Session lock database is blocked")),
				{ once: true },
			);
			request.addEventListener(
				"success",
				() => {
					request.result.addEventListener(
						"versionchange",
						() => {
							request.result.close();
							database = undefined;
						},
						{ once: true },
					);
					resolve(request.result);
				},
				{ once: true },
			);
		});
		return await database;
	} catch (error) {
		database = undefined;
		throw error;
	}
}

async function withDatabaseLock<T>(operation: () => Promise<T>): Promise<T> {
	const db = await lockDatabase();
	// oxlint-disable-next-line promise/avoid-new -- Completion and abort are IndexedDB transaction events.
	return new Promise<T>((resolve, reject) => {
		const transaction = db.transaction("session", "readwrite");
		const store = transaction.objectStore("session");
		let started = false;
		let outcome: { value: T } | { error: unknown } | undefined;
		transaction.addEventListener(
			"abort",
			() => reject(transaction.error ?? new Error("Session lock was aborted")),
			{ once: true },
		);
		transaction.addEventListener(
			"error",
			() => reject(transaction.error ?? new Error("Session lock failed")),
			{ once: true },
		);
		transaction.addEventListener(
			"complete",
			() => {
				if (outcome === undefined) {
					reject(new Error("Session lock ended before the operation"));
				} else if ("error" in outcome) {
					// oxlint-disable-next-line prefer-promise-reject-errors -- Preserve SDK refusal objects and operation errors for the caller.
					reject(outcome.error);
				} else {
					resolve(outcome.value);
				}
			},
			{ once: true },
		);
		const run = async () => {
			try {
				outcome = { value: await operation() };
			} catch (error) {
				outcome = { error };
			}
		};
		// A queued read keeps the write transaction open across async cookie changes. The browser
		// serializes these transactions across tabs and releases them when a tab closes.
		const hold = () => {
			const read = store.get(0);
			read.addEventListener(
				"success",
				() => {
					if (!started) {
						started = true;
						void run();
					}
					if (outcome === undefined) {
						hold();
					}
				},
				{ once: true },
			);
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
