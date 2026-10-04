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
		"Read and accept the current notice in the Hephaestus web app. The extension shows nothing until you do.",
	);
}

/** A result that arrived after the session, instance, workspace or tab it was asked about changed. */
export function stale(): WorkerError {
	return new WorkerError(
		"stale",
		"What you were looking at changed, so the extension now shows the current page.",
	);
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
		"We could not reach Hephaestus. Check your connection and try again.",
	);
}

export function server(status: number): WorkerError {
	return new WorkerError(
		"server",
		status >= 500
			? "We could not get an answer from Hephaestus. Try again in a moment."
			: "Hephaestus did not accept the request. Try again. If it keeps failing, contact your instance operator.",
	);
}

export function toRpcError(error: unknown): RpcError {
	if (error instanceof WorkerError) {
		return { code: error.code, message: error.message };
	}
	return { code: "server", message: "The extension hit an unexpected problem. Try again." };
}
