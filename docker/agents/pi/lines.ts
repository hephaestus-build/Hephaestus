// A copy of ../git/lines.ts: the two images share no build stage, and each is built from its own
// tree. readline treats CR as a delimiter and drops it; the runner's bytes must reach the server
// unchanged, so records are split on exactly one byte and nothing else is interpreted.

/** A piece of one record: `start` opens the record, `end` says the separator followed it. */
export interface Fragment {
	bytes: Buffer;
	start: boolean;
	end: boolean;
}

/**
 * The records of a byte stream in pieces no larger than the chunks that carried them, so a record
 * of any length can be consumed without being held whole.
 */
export async function* fragments(
	source: AsyncIterable<unknown> | Iterable<unknown>,
	separator = 10,
): AsyncGenerator<Fragment> {
	let start = true;
	for await (const chunk of source) {
		if (!Buffer.isBuffer(chunk)) throw new Error("Output must be bytes");
		let offset = 0;
		let end: number;
		while ((end = chunk.indexOf(separator, offset)) !== -1) {
			yield { bytes: chunk.subarray(offset, end), start, end: true };
			start = true;
			offset = end + 1;
		}
		if (offset < chunk.length) {
			yield { bytes: chunk.subarray(offset), start, end: false };
			start = false;
		}
	}
}

/** Whole records; the last one is `terminated: false` when the stream ended without its separator. */
export async function* records(
	source: AsyncIterable<unknown> | Iterable<unknown>,
	separator = 10,
): AsyncGenerator<{ bytes: Buffer; terminated: boolean }> {
	let pending: Buffer[] = [];
	for await (const fragment of fragments(source, separator)) {
		pending.push(fragment.bytes);
		if (!fragment.end) continue;
		yield {
			bytes: pending.length === 1 ? fragment.bytes : Buffer.concat(pending),
			terminated: true,
		};
		pending = [];
	}
	if (pending.length !== 0) yield { bytes: Buffer.concat(pending), terminated: false };
}
