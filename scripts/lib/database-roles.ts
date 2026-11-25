import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import path from "node:path";

import { bounded, CAPTURE_LIMIT_BYTES, howItEnded } from "./process.ts";

const files = path.resolve(import.meta.dirname, "../../deploy/chart/hephaestus/files");

export function databaseRolePasswords() {
	return {
		DATABASE_OWNER_PASSWORD: randomBytes(24).toString("hex"),
		DATABASE_APP_PASSWORD: randomBytes(24).toString("hex"),
	};
}

// Synchronous like every drill's own docker calls, so the drills' spawnSync fixtures see these commands too.
function docker(args: string[], env: Record<string, string | undefined>): void {
	const result = spawnSync("docker", args, {
		encoding: "utf8",
		env: { ...process.env, ...env },
		maxBuffer: CAPTURE_LIMIT_BYTES,
		...bounded(2 * 60 * 1000),
	});
	if (result.status !== 0) {
		throw new Error(
			`docker ${args.join(" ")} ${howItEnded(result)}:\n${result.stdout}${result.stderr}`,
		);
	}
}

export function applyDatabaseRoles(
	container: string,
	database: string,
	env: Record<string, string | undefined> = {},
): void {
	docker(["cp", files, `${container}:/tmp`], {});
	docker(
		[
			"exec",
			"--env",
			"DATABASE_OWNER_PASSWORD",
			"--env",
			"DATABASE_APP_PASSWORD",
			container,
			"sh",
			"/tmp/files/database-roles.sh",
			"-U",
			"root",
			"-d",
			database,
		],
		env,
	);
}
