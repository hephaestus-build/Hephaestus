/**
 * `catch` binds `unknown`, and every diagnostic in the runners wants a line. A rejected SDK promise
 * is often a message-carrying object rather than an Error, so reading `.message` off both is what
 * keeps a real reason out of `[object Object]`.
 */
export function errorText(e: unknown): string {
	if (e instanceof Error) {
		return e.message;
	}
	if (typeof e === "object" && e !== null && "message" in e && typeof e.message === "string") {
		return e.message;
	}
	return String(e);
}
