import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

// A shell script can shadow Docker on Unix. Windows spawn looks for a native executable.
if (process.platform !== "win32") {
	const directory = mkdtempSync(path.join(tmpdir(), "tooling-docker-"));
	const docker = path.join(directory, "docker");
	writeFileSync(
		docker,
		'#!/bin/sh\n: > "$0.hit"\nprintf "%s\\n" "Unexpected Docker call in tooling tests" >&2\nexit 1\n',
		{ mode: 0o755 },
	);
	process.env.PATH = [directory, process.env.PATH].filter(Boolean).join(path.delimiter);
	process.on("exit", () => {
		const called = existsSync(`${docker}.hit`);
		rmSync(directory, { recursive: true, force: true });
		if (called) {
			process.stderr.write("Unexpected Docker call in tooling tests. Stub the command.\n");
			process.exitCode = 1;
		}
	});
}
