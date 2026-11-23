import { randomBytes } from "node:crypto";
import path from "node:path";
import { run } from "./process.ts";

const files = path.resolve(import.meta.dirname, "../../deploy/chart/hephaestus/files");

export function databaseRolePasswords() {
	return {
		DATABASE_OWNER_PASSWORD: randomBytes(24).toString("hex"),
		DATABASE_APP_PASSWORD: randomBytes(24).toString("hex"),
	};
}

export async function applyDatabaseRoles(
	container: string,
	database: string,
	env: Record<string, string | undefined> = {},
): Promise<void> {
	await run("docker", ["cp", files, `${container}:/tmp`]);
	await run(
		"docker",
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
		{ env },
	);
}
