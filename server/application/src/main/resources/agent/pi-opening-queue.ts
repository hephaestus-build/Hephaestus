/** A warm-up belongs to one declared-source set, never to the whole review. */
export class OpeningQueue<T> {
	private readonly pending: T[];
	private readonly signature: (work: T) => string;
	private readonly waitMs: number;
	private readonly openings = new Map<
		string,
		{ ready: boolean; promise: Promise<void>; release: () => void }
	>();

	constructor(queue: readonly T[], signature: (work: T) => string, waitMs = 20_000) {
		this.pending = [...queue];
		this.signature = signature;
		this.waitMs = waitMs;
	}

	async take(): Promise<T | undefined> {
		while (this.pending.length > 0) {
			const index = this.pending.findIndex((work) => {
				const opening = this.openings.get(this.signature(work));
				return opening === undefined || opening.ready;
			});
			if (index !== -1) {
				const work = this.pending.splice(index, 1)[0];
				if (work === undefined) {
					return undefined;
				}
				const key = this.signature(work);
				if (!this.openings.has(key)) {
					const { promise, resolve } = Promise.withResolvers<undefined>();
					const timer = setTimeout(() => this.firstStream(key), this.waitMs);
					this.openings.set(key, {
						ready: false,
						promise,
						release: () => {
							clearTimeout(timer);
							resolve(undefined);
						},
					});
				}
				return work;
			}
			await Promise.race(
				[...this.openings.values()]
					.filter((opening) => !opening.ready)
					.map(async (opening) => opening.promise),
			);
		}
		return undefined;
	}

	firstStream(key: string): void {
		const opening = this.openings.get(key);
		if (opening !== undefined && !opening.ready) {
			opening.ready = true;
			opening.release();
		}
	}

	dispose(): void {
		this.pending.length = 0;
		for (const key of this.openings.keys()) {
			this.firstStream(key);
		}
	}
}
