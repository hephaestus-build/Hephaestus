/** Reads a gateway response within its byte bound, including a response with no Content-Length. */
export async function responseBytes(response: Response, maximum: number): Promise<Buffer> {
	const reader = response.body?.getReader();
	if (reader === undefined) {
		return Buffer.alloc(0);
	}
	const chunks: Buffer[] = [];
	let size = 0;
	try {
		for (;;) {
			const next = await reader.read();
			if (next.done) {
				break;
			}
			size += next.value.byteLength;
			if (size > maximum) {
				throw new Error("The gateway response exceeds its byte bound");
			}
			chunks.push(Buffer.from(next.value));
		}
		return Buffer.concat(chunks, size);
	} finally {
		await reader.cancel().catch(() => undefined);
		reader.releaseLock();
	}
}

export async function responseJson(
	response: Response,
	maximum = 8 * 1024 * 1024,
): Promise<unknown> {
	const bytes = await responseBytes(response, maximum);
	const value: unknown = JSON.parse(bytes.toString("utf8"));
	return value;
}
