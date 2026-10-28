import { z } from "zod";

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

// A closed or stalled tab must not block another tab's renewal indefinitely.
const LEASE_MS = 5000;
const RETRY_MS = 100;
const leaseRecord = z.object({ id: z.uuid(), expiresAt: z.number().nonnegative() });

function now(): number {
	// oxlint-disable-next-line no-restricted-properties -- Tabs compare a persisted lease in wall-clock time.
	return Date.now();
}

async function changeLease(
	db: IDBDatabase,
	id: string,
	action: "acquire" | "release",
): Promise<boolean> {
	// oxlint-disable-next-line promise/avoid-new -- IndexedDB completes transactions through events.
	return new Promise<boolean>((resolve, reject) => {
		const transaction = db.transaction("session", "readwrite");
		const store = transaction.objectStore("session");
		let acquired = false;
		transaction.addEventListener("complete", () => resolve(acquired), { once: true });
		transaction.addEventListener(
			"abort",
			() => reject(transaction.error ?? new Error("Session lease aborted")),
			{ once: true },
		);
		transaction.addEventListener(
			"error",
			() => reject(transaction.error ?? new Error("Session lease failed")),
			{ once: true },
		);
		const read = store.get(0);
		read.addEventListener(
			"success",
			() => {
				const current = leaseRecord.safeParse(read.result).data;
				if (action === "release") {
					if (current?.id === id) {
						store.delete(0);
					}
				} else if (current === undefined || current.expiresAt <= now()) {
					store.put({ id, expiresAt: now() + LEASE_MS }, 0);
					acquired = true;
				}
			},
			{ once: true },
		);
	});
}

async function withDatabaseLock<T>(operation: () => Promise<T>): Promise<T> {
	let db: IDBDatabase;
	let id: string;
	try {
		db = await lockDatabase();
		id = crypto.randomUUID();
	} catch {
		return operation();
	}
	const deadline = performance.now() + LEASE_MS;
	let acquired = false;
	while (!acquired && performance.now() < deadline) {
		try {
			acquired = await changeLease(db, id, "acquire");
		} catch {
			return operation();
		}
		if (!acquired) {
			// oxlint-disable-next-line promise/avoid-new -- A timer spaces bounded lease attempts instead of holding an IndexedDB transaction open.
			await new Promise<void>((resolve) => {
				setTimeout(resolve, RETRY_MS);
			});
		}
	}
	if (!acquired) {
		return operation();
	}
	try {
		return await operation();
	} finally {
		try {
			await changeLease(db, id, "release");
		} catch {
			// Lease expiry releases other tabs even when storage fails during cleanup.
		}
	}
}

/** Serialize cookie changes where browser locks are available; renewal still works without them. */
export async function withSessionLock<T>(operation: () => Promise<T>): Promise<T> {
	// oxlint-disable-next-line typescript/no-unnecessary-condition -- Web Locks requires a secure context.
	if (navigator.locks === undefined) {
		return withDatabaseLock(operation);
	}
	let started = false;
	try {
		return await navigator.locks.request(
			"hephaestus-session",
			{ signal: AbortSignal.timeout(LEASE_MS) },
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
		return withDatabaseLock(operation);
	}
}
