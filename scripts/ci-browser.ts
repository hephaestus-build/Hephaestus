import { readFile } from "node:fs/promises";
import path from "node:path";

import { asRecord, asString, parseJson } from "./lib/json.ts";
import { run } from "./lib/process.ts";

export function browserImage(pin: unknown, version: string): string {
	const record = asRecord(pin, "Playwright image");
	const repository = asString(record.repository, "Playwright repository");
	const tag = asString(record.tag, "Playwright tag");
	const digest = asString(record.digest, "Playwright digest");
	if (repository !== "mcr.microsoft.com/playwright" || !/^sha256:[a-f0-9]{64}$/u.test(digest)) {
		throw new Error("Use the official Playwright image pinned by digest");
	}
	if (tag !== `v${version}-noble`) {
		throw new Error("The Playwright image must match webapp/package.json");
	}
	return `${repository}:${tag}@${digest}`;
}

if (import.meta.main) {
	const uid = process.getuid?.();
	const gid = process.getgid?.();
	if (process.platform !== "linux" || uid === undefined || gid === undefined) {
		throw new Error("CI browser commands require a Linux Docker host");
	}
	const root = path.resolve(import.meta.dirname, "..");
	const pkg = asRecord(
		parseJson(await readFile(path.join(root, "webapp/package.json"), "utf8")),
		"webapp package",
	);
	const version = asString(
		asRecord(pkg.devDependencies, "webapp dependencies").playwright,
		"Playwright version",
	);
	const image = browserImage(
		parseJson(await readFile(path.join(root, ".github/playwright-image.json"), "utf8")),
		version,
	);
	const command = process.argv.slice(2);
	if (command.length === 0) {
		throw new Error("usage: node scripts/ci-browser.ts pull|<command> [arguments...]");
	}
	if (command[0] === "pull") {
		await run("docker", ["pull", image]);
	} else {
		// Only the test process enters the image. The packaged JAR and PostgreSQL stay on the host.
		// Host networking preserves their loopback URLs; --init reaps Chromium's child processes.
		const args = [
			"run",
			"--rm",
			"--init",
			"--network=host",
			"--ipc=host",
			"--user",
			`${uid}:${gid}`,
			"--volume",
			`${root}:${root}`,
			"--workdir",
			process.cwd(),
			"--volume",
			`${path.dirname(process.execPath)}:/opt/ci-node:ro`,
			"--env",
			`PATH=/opt/ci-node:${root}/node_modules/.bin:/usr/local/bin:/usr/bin:/bin`,
			"--env",
			"HOME=/tmp",
			"--env",
			"PLAYWRIGHT_BROWSERS_PATH=/ms-playwright",
		];
		for (const name of [
			"CI",
			"GITHUB_ACTIONS",
			"GITHUB_REPOSITORY",
			"GITHUB_WORKSPACE",
			"GITHUB_RUN_ID",
			"GITHUB_RUN_ATTEMPT",
			"GITHUB_EVENT_NAME",
			"GITHUB_REF",
			"GITHUB_BASE_REF",
			"GITHUB_HEAD_REF",
			"GITHUB_SHA",
			"E2E_SERVER_URL",
			"E2E_PORT",
			"E2E_BASE_URL",
			"E2E_WEB_APP_URL",
		]) {
			if (process.env[name] !== undefined) {
				args.push("--env", name);
			}
		}
		await run("docker", [...args, image, ...command]);
	}
}
