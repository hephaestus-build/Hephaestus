import type { RpcError, RpcErrorCode } from "~/shared/rpc";

/**
 * A failure the worker turns into a coded RPC error. The code is what a view branches on; the
 * message is shown as written, so it names what happened and what to do, never a status number.
 */
export class WorkerError extends Error {
	readonly code: RpcErrorCode;

	constructor(code: RpcErrorCode, message: string) {
		super(message);
		this.name = "WorkerError";
		this.code = code;
	}
}

export function signedOut(): WorkerError {
	return new WorkerError("signed-out", "Sign in to Hephaestus again to continue.");
}

export function consentRequired(): WorkerError {
	return new WorkerError(
		"consent-required",
		"Hephaestus needs you to read its current notice in the web app before it can show anything.",
	);
}

/** A result that arrived after the session, instance, workspace or tab it was asked about changed. */
export function stale(): WorkerError {
	return new WorkerError("stale", "What you were looking at changed; showing the current page.");
}

/** The server said no to this reader for this work: not theirs to see or change. */
export function forbidden(
	message = "Hephaestus did not allow this for your account.",
): WorkerError {
	return new WorkerError("forbidden", message);
}

export function network(): WorkerError {
	return new WorkerError(
		"network",
		"Hephaestus could not be reached. Check your connection and try again.",
	);
}

export function server(status: number): WorkerError {
	return new WorkerError(
		"server",
		status >= 500
			? "Hephaestus ran into a problem answering. Try again in a moment."
			: "Hephaestus refused the request.",
	);
}

export function toRpcError(error: unknown): RpcError {
	if (error instanceof WorkerError) {
		return { code: error.code, message: error.message };
	}
	return { code: "server", message: "Something went wrong inside the extension." };
}
