import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { run } from "./lib/process.ts";

const UBUNTU_ARCHIVE = "https://archive.ubuntu.com/ubuntu/";

/**
 * The runner's Ubuntu sources route through its mirror list. The copy names the Ubuntu archive directly and keeps
 * every other deb822 field as written, so Signed-By still decides which signatures apt accepts.
 */
export function directArchiveSources(sources: string): string {
	const stanzas = sources.split(/\n[ \t]*\n/u);
	if (!stanzas.some((stanza) => /^URIs:/imu.test(stanza))) {
		throw new Error("The Ubuntu sources name no URIs to point at the archive");
	}
	for (const stanza of stanzas) {
		if (/^[A-Za-z][^:\n]*:/mu.test(stanza) && !/^URIs:/imu.test(stanza)) {
			throw new Error("An Ubuntu source stanza has no URIs field to point at the archive");
		}
	}
	return sources.replaceAll(/^URIs:[^\n]*(?:\n[ \t]+(?=\S)[^\n]*)*/gimu, `URIs: ${UBUNTU_ARCHIVE}`);
}

if (import.meta.main) {
	const directory = await mkdtemp(path.join(tmpdir(), "playwright-apt-"));
	try {
		const sources = path.join(directory, "sources");
		await mkdir(sources);
		await writeFile(
			path.join(sources, "ubuntu.sources"),
			directArchiveSources(await readFile("/etc/apt/sources.list.d/ubuntu.sources", "utf8")),
		);
		const config = path.join(directory, "apt.conf");
		await writeFile(
			config,
			`Dir::Etc::sourcelist "/dev/null";\nDir::Etc::sourceparts ${JSON.stringify(sources)};\n`,
		);
		await run("sudo", [
			"env",
			`APT_CONFIG=${config}`,
			process.execPath,
			path.resolve("webapp/node_modules/playwright/cli.js"),
			"install-deps",
			"chromium",
		]);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}
