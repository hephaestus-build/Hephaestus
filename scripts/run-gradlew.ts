// Select the platform wrapper and run it from server/, regardless of the caller's directory.

import { spawnSync } from "node:child_process";
import path from "node:path";
import process from "node:process";

const gradlewDir = path.resolve(import.meta.dirname, "..", "server");
const isWindows = process.platform === "win32";

function main(): void {
	const runtimePath = path.dirname(process.execPath);
	const inheritedPath = process.env.PATH ?? (isWindows ? undefined : "/usr/bin:/bin");
	// Windows batch wrappers require a shell.
	const result = spawnSync(isWindows ? "gradlew.bat" : "./gradlew", process.argv.slice(2), {
		stdio: "inherit",
		cwd: gradlewDir,
		// Node shims can select another runtime after a test changes HOME or its working directory.
		env: {
			...process.env,
			PATH:
				inheritedPath === undefined
					? runtimePath
					: `${runtimePath}${path.delimiter}${inheritedPath}`,
		},
		shell: isWindows,
	});

	if (result.error) {
		if ("code" in result.error && result.error.code === "ENOENT") {
			console.error(`Gradle wrapper not found in ${gradlewDir}. Is the Gradle wrapper installed?`);
		} else {
			console.error(`Failed to run Gradle wrapper: ${result.error.message}`);
		}
		process.exitCode = 1;
		return;
	}

	if (result.signal) {
		process.kill(process.pid, result.signal);
		return;
	}

	process.exitCode = result.status ?? 1;
}

main();
