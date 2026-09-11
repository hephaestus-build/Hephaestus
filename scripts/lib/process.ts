import { execFile, spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

/**
 * Node caps a captured subprocess at 1 MiB and throws `ERR_CHILD_PROCESS_STDIO_MAXBUFFER` past it.
 * Nothing captured here has a useful size limit — a `gh api` listing, a git diff, an SBOM — and the
 * default has broken the release pipeline three separate times. The ceiling is stated once, here,
 * rather than rediscovered per call site. It stays finite on purpose: an unbounded capture of a
 * runaway process is its own failure.
 */
export const CAPTURE_LIMIT_BYTES = 256 * 1024 * 1024;

export interface RunOptions {
	cwd?: string;
	env?: Record<string, string | undefined>;
	stdin?: "inherit" | "ignore";
	stdout?: "inherit" | "ignore";
	stderr?: "inherit" | "ignore";
	signal?: AbortSignal;
}

export async function run(
	command: string,
	args: string[],
	options: RunOptions = {},
): Promise<void> {
	const child = spawn(command, args, {
		cwd: options.cwd,
		env: { ...process.env, ...options.env },
		stdio: [options.stdin ?? "inherit", options.stdout ?? "inherit", options.stderr ?? "inherit"],
		signal: options.signal,
	});
	await new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("exit", (code, signal) =>
			code === 0
				? resolve()
				: reject(new Error(`${command} exited with ${signal ?? `code ${code}`}`)),
		);
	});
}

export async function succeeds(
	command: string,
	args: string[],
	options: RunOptions = {},
): Promise<boolean> {
	try {
		await run(command, args, { ...options, stdout: "ignore", stderr: "ignore" });
		return true;
	} catch {
		return false;
	}
}

export async function output(
	command: string,
	args: string[],
	options: RunOptions = {},
): Promise<string> {
	const { stdout } = await execFileAsync(command, args, {
		cwd: options.cwd,
		env: { ...process.env, ...options.env },
		signal: options.signal,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	return stdout;
}

/**
 * This repository's CLI, as an argument for `process.execPath`.
 *
 * Resolved as a module rather than looked up on PATH: it is a dependency of this repository, so it
 * is on PATH only for what the package manager starts, and process creation without a shell finds
 * neither the extensionless script nor the `.cmd` shim a bin directory holds on Windows. Throws
 * when the dependency is absent, which is the one case a caller can act on.
 */
export function repositoryCli(): string {
	return fileURLToPath(import.meta.resolve("vite-plus/bin"));
}
