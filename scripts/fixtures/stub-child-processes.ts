import childProcess, { ChildProcess, type SpawnSyncOptions } from "node:child_process";
import { syncBuiltinESMExports } from "node:module";

interface Result {
	status: number;
	stdout: string;
	stderr: string;
}

/**
 * Install in a fixture subprocess before importing the script under test.
 * Unconfigured launch methods fail instead of falling through to the host.
 */
export function stubChildProcesses(
	command: (executable: string, args: string[], options: SpawnSyncOptions) => Result,
	execFileSync?: (executable: string, args: string[]) => string,
): void {
	const blocked = Object.entries(childProcess)
		.filter(([name, value]) => /^(?:spawn|exec|fork)/u.test(name) && typeof value === "function")
		.map(([name]) => [
			name,
			(executable: string) => {
				throw new Error(`Unexpected child process: ${name}(${executable})`);
			},
		]);
	Object.assign(childProcess, Object.fromEntries(blocked), {
		spawnSync: (executable: string, args: string[], options: SpawnSyncOptions = {}) => ({
			signal: null,
			...command(executable, args, options),
		}),
		spawn(executable: string, args: string[], options: SpawnSyncOptions = {}) {
			const result = command(executable, args, options);
			const child = new ChildProcess();
			queueMicrotask(() => {
				child.emit("exit", result.status, null);
			});
			return child;
		},
		...(execFileSync === undefined ? {} : { execFileSync }),
	});
	syncBuiltinESMExports();
}
