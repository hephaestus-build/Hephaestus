import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { isSet } from "./env.ts";
import { exitStatus } from "./process.ts";

// Bounds the packaged launch only; a local Gradle run may first compile the server.
const MIGRATION_DEADLINE_MS = 6 * 60 * 1000;
const OUTPUT_LIMIT_BYTES = 64 * 1024 * 1024;

function migrate(
	command: string,
	args: string[],
	options: { cwd?: string; env?: NodeJS.ProcessEnv; timeout?: number } = {},
) {
	const result = spawnSync(command, args, {
		...options,
		encoding: "utf8",
		maxBuffer: OUTPUT_LIMIT_BYTES,
	});
	if (result.error !== undefined) {
		throw result.error;
	}
	if (result.status !== 0) {
		throw new Error(
			`Database migration exited with ${exitStatus(result.status, result.signal)}:\n${result.stdout}${result.stderr}`,
		);
	}
}

// Loader and Liquibase properties, JVM option variables and ambient files could change the
// packaged migration source. Launch in an owned empty directory with only PATH inherited.

export function databaseMigration(localTimeout?: number): (port: number) => void {
	const jar = process.env.HEPHAESTUS_APPLICATION_JAR;
	if (jar === undefined) {
		if (process.env.CI === "true") {
			throw new Error("HEPHAESTUS_APPLICATION_JAR must name the packaged server in CI");
		}
		return (port) => {
			migrate(
				"node",
				[
					"scripts/run-gradlew.ts",
					":application:liquibaseUpdate",
					`-PpostgresPort=${port}`,
					"--quiet",
				],
				{ timeout: localTimeout },
			);
		};
	}
	// Resolved now: the launcher runs in its own empty directory.
	const archive = path.resolve(jar);
	const file = statSync(archive, { throwIfNoEntry: false });
	if (!archive.endsWith(".jar") || file?.isFile() !== true || file.size === 0) {
		throw new Error(`HEPHAESTUS_APPLICATION_JAR does not name a non-empty JAR file: ${jar}`);
	}
	const javaHome = process.env.JAVA_HOME;
	const java = isSet(javaHome) ? path.join(javaHome, "bin", "java") : "java";
	return (port) => {
		const home = mkdtempSync(path.join(tmpdir(), "hephaestus-migration-"));
		try {
			migrate(
				java,
				[
					"-Xmx512m",
					"-Dloader.main=liquibase.integration.commandline.Main",
					"-Dloader.path=BOOT-INF/lib",
					`-Dloader.home=${home}`,
					"-cp",
					archive,
					"org.springframework.boot.loader.launch.PropertiesLauncher",
					"--changeLogFile=db/master.xml",
					`--url=jdbc:postgresql://127.0.0.1:${port}/hephaestus`,
					"--username=root",
					"--password=root",
					"update",
				],
				{ cwd: home, env: { PATH: process.env.PATH }, timeout: MIGRATION_DEADLINE_MS },
			);
		} finally {
			rmSync(home, { recursive: true, force: true });
		}
	};
}
