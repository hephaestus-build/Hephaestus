import { createHash } from "node:crypto";
import { lstat, readFile, rm } from "node:fs/promises";
import { setTimeout } from "node:timers/promises";

import { output } from "./lib/process.ts";

const BUDGET_MS = 360_000;
const ATTEMPT_MS = 120_000;
const MAX_ATTEMPTS = 3;

interface Dependencies {
	execute?: (args: string[], signal: AbortSignal) => Promise<void>;
	now?: () => number;
	sleep?: (milliseconds: number) => Promise<void>;
	random?: () => number;
}

/**
 * An OCI subject by digest: the registry keeps it immutable, so there is no file to watch. It starts
 * with a registry name, never with `-`, so cosign cannot read it as a flag.
 */
const ociDigest = /^[a-z0-9][^\s@]*@sha256:[a-f0-9]{64}$/u;

const isDigestSign = (args: readonly string[]) =>
	args.length === 3 && args[0] === "sign" && args[1] === "--yes" && ociDigest.test(args[2] ?? "");

function signingFiles(args: readonly string[]): { input?: string; bundle?: string } {
	if (isDigestSign(args)) {
		return {};
	}
	if (
		args.length === 7 &&
		args[0] === "attest" &&
		args[1] === "--yes" &&
		args[2] === "--type" &&
		args[3] === "spdxjson" &&
		args[4] === "--predicate" &&
		ociDigest.test(args[6] ?? "")
	) {
		return { input: args[5] ?? "" };
	}
	if (
		args.length === 5 &&
		args[0] === "sign-blob" &&
		args[1] === "--yes" &&
		args[2] === "--bundle"
	) {
		const bundle = args[3] ?? "";
		const input = args[4] ?? "";
		if (bundle !== "" && input !== "" && bundle !== input) {
			return { input, bundle };
		}
	}
	throw new Error("Expected a release keyless attest, sign or sign-blob command");
}

async function digest(file: string | undefined): Promise<string | undefined> {
	if (file === undefined) {
		return undefined;
	}
	const info = await lstat(file);
	if (!info.isFile() || info.isSymbolicLink()) {
		throw new Error("Signing input must be a regular file");
	}
	return createHash("sha256")
		.update(await readFile(file))
		.digest("hex");
}

async function exists(file: string): Promise<boolean> {
	try {
		await lstat(file);
		return true;
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") {
			return false;
		}
		throw error;
	}
}

function retryable(error: unknown): boolean {
	if (!(error instanceof Error) || !("stderr" in error) || typeof error.stderr !== "string") {
		return false;
	}
	const text = error.stderr;
	return (
		/Post "https:\/\/rekor\.sigstore\.dev\/api\/v1\/log\/entries":[^\n]*status 502:/u.test(text) ||
		/\[POST \/api\/v1\/log\/entries\]\[409\] createLogEntryConflict[^\n]*an equivalent entry already exists in the transparency log/u.test(
			text,
		)
	);
}

async function requireBundle(bundle: string | undefined): Promise<void> {
	if (bundle === undefined) {
		return;
	}
	await digest(bundle);
	const document: unknown = JSON.parse(await readFile(bundle, "utf8"));
	if (document === null || typeof document !== "object" || Array.isArray(document)) {
		throw new Error("Cosign did not produce a JSON bundle");
	}
}

async function discardPartialBundle(bundle: string | undefined, cause: unknown): Promise<void> {
	if (bundle === undefined || !(await exists(bundle))) {
		return;
	}
	const info = await lstat(bundle);
	if (!info.isFile() || info.isSymbolicLink()) {
		throw new Error("Unexpected signing bundle type", { cause });
	}
	await rm(bundle);
}

/** Retry uncertain Rekor uploads with fresh keyless processes, never as proof of a signature. */
export async function keylessSign(args: string[], dependencies: Dependencies = {}): Promise<void> {
	const { input, bundle } = signingFiles(args);
	const expected = await digest(input);
	if (bundle !== undefined && (await exists(bundle))) {
		throw new Error("Signing bundle already exists");
	}
	const now = dependencies.now ?? Date.now;
	const sleep =
		dependencies.sleep ??
		(async (milliseconds) => {
			await setTimeout(milliseconds);
		});
	const random = dependencies.random ?? Math.random;
	const execute =
		dependencies.execute ??
		(async (commandArgs, signal) => {
			await output("cosign", commandArgs, { signal });
		});
	const deadline = now() + BUDGET_MS;
	for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
		if ((await digest(input)) !== expected) {
			throw new Error("Signing input changed");
		}
		const remaining = deadline - now();
		if (remaining <= 0) {
			throw new Error("Keyless signing time budget exhausted");
		}
		try {
			// No --key is accepted: Cosign creates a new ephemeral key in each process.
			await execute([...args], AbortSignal.timeout(Math.min(ATTEMPT_MS, remaining)));
			if ((await digest(input)) !== expected) {
				throw new Error("Signing input changed");
			}
			await requireBundle(bundle);
			return;
		} catch (error) {
			await discardPartialBundle(bundle, error);
			if (!retryable(error) || attempt === MAX_ATTEMPTS) {
				throw error;
			}
			const delay = 30_000 + Math.floor(random() * 5000);
			if (deadline - now() <= delay) {
				throw new Error("Keyless signing time budget exhausted", { cause: error });
			}
			console.error(`Rekor upload uncertain; retry ${attempt + 1}/${MAX_ATTEMPTS} after backoff`);
			await sleep(delay);
		}
	}
}

if (import.meta.main) {
	await keylessSign(process.argv.slice(2));
}
