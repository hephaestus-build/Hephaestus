import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

import { asArray, asRecord, asStringArray } from "./lib/json.ts";
import { loadTasks } from "./lib/task-graph.ts";

function preparation(args: string[], imageExists = true): (readonly string[])[] {
	const script = new URL("./postgres-backup-restore-test.ts", import.meta.url).href;
	const result = spawnSync(process.execPath, ["--input-type=module"], {
		input: `
import childProcess from "node:child_process";
import { syncBuiltinESMExports } from "node:module";
const commands = [];
childProcess.spawnSync = (command, args) => {
  commands.push([command, ...args]);
  let stdout = "";
  let status = 0;
  if (command === "node") return { status: 1, stdout: "", stderr: "stop before migrations" };
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
  if (!String(error).includes(${JSON.stringify(imageExists ? "stop before migrations" : "missing image")})) throw error;
}
process.stdout.write(JSON.stringify(commands));
`,
		encoding: "utf8",
	});
	assert.equal(result.status, 0, result.stderr);
	const value: unknown = JSON.parse(result.stdout);
	return asArray(value, "commands").map((command) => asStringArray(command, "command"));
}

await test("local drill builds one PostgreSQL image and lets Gradle prepare its inputs", async () => {
	const tasks = await loadTasks();
	const task = asRecord(tasks["test:postgres-restore"], "restore task");
	assert.deepEqual(task.dependsOn, []);
	const commands = preparation([]);
	assert.equal(commands.filter((command) => command[1] === "build").length, 1);
	const gradle = commands.filter((command) => command[0] === "node");
	assert.equal(gradle.length, 1);
	assert.ok(gradle[0]?.includes(":application:liquibaseUpdate"));
	assert.equal(commands.filter((command) => command[1] === "rmi").length, 1);
});

await test("restored CI artifacts and PostgreSQL image are reused, not rebuilt or deleted", () => {
	const commands = preparation(["--target-image", "hephaestus-postgres:ci"]);
	assert.deepEqual(commands[0], ["docker", "image", "inspect", "hephaestus-postgres:ci"]);
	assert.equal(commands.filter((command) => command[1] === "build").length, 0);
	assert.equal(commands.filter((command) => command.includes("install")).length, 0);
	assert.equal(commands.filter((command) => command[1] === "rmi").length, 0);
	assert.equal(
		commands.some((command) => command[1] === "rmi" && command.includes("hephaestus-postgres:ci")),
		false,
	);
});

await test("a missing explicitly supplied image fails instead of silently building a replacement", () => {
	const commands = preparation(["--target-image", "missing:ci"], false);
	assert.equal(
		commands.some((command) => command[1] === "build"),
		false,
	);
	assert.equal(
		commands.some((command) => command[0] === "node"),
		false,
	);
});
