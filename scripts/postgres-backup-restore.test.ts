import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { asArray, asRecord, asString, asStringArray } from "./lib/json.ts";
import { loadTasks } from "./lib/task-graph.ts";

const fixtures = mkdtempSync(path.join(tmpdir(), "postgres-drill-"));
after(() => rmSync(fixtures, { recursive: true, force: true }));
const jar = path.join(fixtures, "hephaestus-application-0.0.0.jar");
writeFileSync(jar, "PK");
const local = { CI: undefined, HEPHAESTUS_APPLICATION_JAR: undefined };
// Ambient settings that would otherwise choose another main class, changelog or JVM option.
const packaged = {
	CI: "true",
	HEPHAESTUS_APPLICATION_JAR: jar,
	JAVA_HOME: undefined,
	LOADER_PATH: "/elsewhere",
	LIQUIBASE_COMMAND_CHANGELOG_FILE: "other.xml",
	JAVA_TOOL_OPTIONS: "-Dloader.main=other",
};

function preparation(
	args: string[],
	environment: Record<string, string | undefined>,
	failure = "stop before migrations",
	imageExists = true,
) {
	const script = new URL("postgres-backup-restore-test.ts", import.meta.url).href;
	const result = spawnSync(process.execPath, ["--input-type=module"], {
		input: `
import childProcess from "node:child_process";
import { syncBuiltinESMExports } from "node:module";
const commands = [];
let java;
childProcess.spawnSync = (command, args, options = {}) => {
  commands.push([command, ...args]);
  if (command === "java") java = { cwd: options.cwd, env: Object.keys(options.env ?? {}) };
  if (command === "node" || command === "java") {
    return { status: 1, signal: null, stdout: "", stderr: "stop before migrations" };
  }
  let stdout = "";
  let status = 0;
  if (args[0] === "image" && args[1] === "inspect" && !${imageExists}) status = 1;
  if (args[0] === "port") stdout = "127.0.0.1:54321";
  if (args.includes("SHOW server_version_num")) stdout = "180000";
  return { status, stdout, stderr: status ? "missing image" : "" };
};
syncBuiltinESMExports();
process.argv = ["node", ${JSON.stringify(script)}, ...${JSON.stringify(args)}];
try {
  await import(${JSON.stringify(script)});
  throw Error("expected fixture failure");
} catch (error) {
  if (!String(error).includes(${JSON.stringify(failure)})) throw error;
}
process.stdout.write(JSON.stringify({ commands, java }));
`,
		encoding: "utf8",
		env: { ...process.env, ...environment },
	});
	assert.equal(result.status, 0, result.stderr);
	const parsed: unknown = JSON.parse(result.stdout);
	const value = asRecord(parsed, "preparation");
	const java = value.java === undefined ? undefined : asRecord(value.java, "java launch");
	return {
		commands: asArray(value.commands, "commands").map((command) =>
			asStringArray(command, "command"),
		),
		java: java && { cwd: asString(java.cwd, "java cwd"), env: asStringArray(java.env, "java env") },
	};
}

void test("local drill builds one PostgreSQL image and lets Gradle prepare its inputs", async () => {
	const tasks = await loadTasks();
	const task = asRecord(tasks["test:postgres-restore"], "restore task");
	assert.deepEqual(task.dependsOn, []);
	const { commands, java } = preparation([], local);
	assert.equal(commands.filter((command) => command[1] === "build").length, 1);
	const gradle = commands.filter((command) => command[0] === "node");
	assert.equal(gradle.length, 1);
	assert.ok(gradle[0]?.includes(":application:liquibaseUpdate") === true);
	assert.equal(java, undefined);
	assert.equal(commands.filter((command) => command[1] === "rmi").length, 1);
});

void test("restored CI artifacts and PostgreSQL image are reused, not rebuilt or deleted", () => {
	const { commands, java } = preparation(["--target-image", "hephaestus-postgres:ci"], packaged);
	assert.deepEqual(commands[0], ["docker", "image", "inspect", "hephaestus-postgres:ci"]);
	assert.equal(commands.filter((command) => command[1] === "build").length, 0);
	assert.equal(commands.filter((command) => command.includes("install")).length, 0);
	assert.equal(commands.filter((command) => command[1] === "rmi").length, 0);
	assert.equal(
		commands.some((command) => command[1] === "rmi" && command.includes("hephaestus-postgres:ci")),
		false,
	);
	// The supplied JAR migrates the database through its own launcher; Gradle never runs.
	assert.equal(
		commands.some((command) => command[0] === "node"),
		false,
	);
	const launch = commands.find((command) => command[0] === "java");
	assert.ok(launch !== undefined && java !== undefined);
	for (const argument of [
		"-Dloader.main=liquibase.integration.commandline.Main",
		`-Dloader.home=${java.cwd}`,
		"org.springframework.boot.loader.launch.PropertiesLauncher",
		"--changeLogFile=db/master.xml",
		"update",
	]) {
		assert.ok(launch.includes(argument), argument);
	}
	assert.equal(launch[launch.indexOf("-cp") + 1], jar);
	assert.deepEqual(java.env, ["PATH"]);
	assert.equal(
		existsSync(java.cwd),
		false,
		"the launcher's own directory is removed after a failure",
	);
});

void test("CI without the packaged server fails before any container starts", () => {
	const { commands } = preparation(
		["--target-image", "hephaestus-postgres:ci"],
		{ ...packaged, HEPHAESTUS_APPLICATION_JAR: undefined },
		"HEPHAESTUS_APPLICATION_JAR must name the packaged server",
	);
	assert.deepEqual(commands, []);
});

void test("an unusable supplied JAR is refused instead of falling back to Gradle", () => {
	const empty = path.join(fixtures, "empty.jar");
	writeFileSync(empty, "");
	for (const candidate of ["", " ", path.join(fixtures, "missing.jar"), empty, fixtures]) {
		const { commands } = preparation(
			[],
			{ ...local, HEPHAESTUS_APPLICATION_JAR: candidate },
			"does not name a non-empty JAR file",
		);
		assert.deepEqual(commands, [], candidate);
	}
});

void test("a missing explicitly supplied image fails instead of silently building a replacement", () => {
	const { commands } = preparation(["--target-image", "missing:ci"], local, "missing image", false);
	assert.equal(
		commands.some((command) => command[1] === "build"),
		false,
	);
	assert.equal(
		commands.some((command) => command[0] === "node"),
		false,
	);
});
