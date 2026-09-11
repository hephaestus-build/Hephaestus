import { copyFile, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { run } from "./lib/process.ts";

const directory = await mkdtemp(join(tmpdir(), "playwright-apt-"));
try {
	const sources = join(directory, "sources");
	await mkdir(sources);
	await copyFile("/etc/apt/sources.list.d/ubuntu.sources", join(sources, "ubuntu.sources"));
	const config = join(directory, "apt.conf");
	await writeFile(
		config,
		`Dir::Etc::sourcelist "/dev/null";\nDir::Etc::sourceparts ${JSON.stringify(sources)};\n`,
	);
	await run("sudo", [
		"env",
		`APT_CONFIG=${config}`,
		process.execPath,
		resolve("webapp/node_modules/playwright/cli.js"),
		"install-deps",
		"chromium",
	]);
} finally {
	await rm(directory, { recursive: true, force: true });
}
