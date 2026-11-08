import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cp, mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { after, before, test } from "node:test";
import { pathToFileURL } from "node:url";

import { z } from "zod";

import { REQUESTED_PULL_REQUEST } from "../../stage.ts";

const install = path.resolve(import.meta.dirname, "../..");
const repository = path.resolve(install, "../../..");
let root = "";
let source = "";
let artifact = "";
let practices = "";
let dependencies = "";

function runNode(entry: string, flags: string[] = [], args: string[] = []) {
	const result = spawnSync(process.execPath, [...flags, entry, ...args], {
		cwd: repository,
		encoding: "utf8",
		timeout: 20_000,
		env: { COMPILED_PARENT_SENTINEL: "parent-only" },
	});
	assert.ifError(result.error);
	return result;
}

before(async () => {
	await mkdir(path.join(repository, "tmp"), { recursive: true });
	root = await mkdtemp(path.join(repository, "tmp/compiled-toolkit-"));
	dependencies = await realpath(path.join(repository, "node_modules"));
	source = path.join(root, "source");
	await cp(install, source, {
		recursive: true,
		filter: (file) => !["node_modules", "dist"].includes(path.basename(file)),
	});
	await symlink(path.join(repository, "node_modules"), path.join(source, "node_modules"));
	const built = runNode(
		path.join(source, "build.ts"),
		[],
		[path.join(repository, "node_modules/typescript/bin/tsc")],
	);
	assert.equal(built.status, 0, built.stderr);
	artifact = path.join(source, "dist");
	practices = path.join(root, "stage/practices");
	await mkdir(practices, { recursive: true });
	for (const name of ["lib", "kits"]) {
		await symlink(path.join(artifact, name), path.join(root, "stage", name));
	}
});

after(async () => {
	if (root !== "") {
		await rm(root, { recursive: true, force: true });
	}
});

void test("the image build removes obsolete JavaScript before rebuilding", async () => {
	await writeFile(path.join(artifact, "obsolete.js"), "obsolete");
	const built = runNode(
		path.join(source, "build.ts"),
		[],
		[path.join(repository, "node_modules/typescript/bin/tsc")],
	);
	assert.equal(built.status, 0, built.stderr);
	await assert.rejects(readFile(path.join(artifact, "obsolete.js")), { code: "ENOENT" });
});

void test("compiled public imports work and private imports remain refused", async () => {
	const manifest: unknown = JSON.parse(await readFile(path.join(artifact, "package.json"), "utf8"));
	const { exports } = z.object({ exports: z.record(z.string(), z.string()) }).parse(manifest);
	const allowed = Object.keys(exports).map((name) =>
		name === "." ? "@hephaestus/practice-scripts" : `@hephaestus/practice-scripts/${name.slice(2)}`,
	);
	const guard = pathToFileURL(path.join(artifact, "lib/core/script-imports.js")).href;
	const privateFile = path.join(artifact, "lib/core/script-run.js");
	const specifiers = [
		...allowed,
		"node:fs",
		"@hephaestus/practice-scripts/lib/core/script-run.js",
		path.relative(practices, privateFile),
		pathToFileURL(privateFile).href,
	];
	for (const [index, specifier] of specifiers.entries()) {
		const program = path.join(practices, `import-${index}.js`);
		const entry = path.join(root, `import-${index}.mjs`);
		await writeFile(
			program,
			`await import(${JSON.stringify(specifier)}); console.log("public import loaded");\n`,
		);
		await writeFile(
			entry,
			`import { guardScriptImports } from ${JSON.stringify(guard)}; guardScriptImports(); await import(${JSON.stringify(pathToFileURL(program).href)});\n`,
		);
		const result = runNode(entry, [
			"--no-experimental-strip-types",
			"--permission",
			`--allow-fs-read=${root}`,
			`--allow-fs-read=${path.join(repository, "node_modules")}`,
			`--allow-fs-read=${dependencies}`,
		]);
		if (allowed.includes(specifier)) {
			assert.equal(result.status, 0, result.stderr);
			assert.match(result.stdout, /public import loaded/u);
		} else {
			assert.notEqual(result.status, 0);
			assert.match(result.stderr, /A practice script can import only/u);
		}
	}
});

function registerChild(extension: "js" | "ts") {
	void test(`the compiled child runs a ${extension} program with its file grants`, async () => {
		const program = path.join(practices, `own.${extension}`);
		await writeFile(path.join(practices, "sibling.ts"), "private sibling");
		await writeFile(
			program,
			`import { definePracticeScript } from "@hephaestus/practice-scripts";
export default definePracticeScript({ meta: {}, run: async () => {
  const own${extension === "ts" ? ": string" : ""} = process.getBuiltinModule("fs").readFileSync(import.meta.filename, "utf8");
  const denied = (action) => { try { action(); return false; } catch (error) { return error.code === "ERR_ACCESS_DENIED"; } };
  return {
    own: own.includes("definePracticeScript"),
    siblingDenied: denied(() => process.getBuiltinModule("fs").readFileSync(new URL("sibling.ts", import.meta.url))),
    writeDenied: denied(() => process.getBuiltinModule("fs").writeFileSync(import.meta.filename, "changed")),
    spawnDenied: denied(() => process.getBuiltinModule("child_process").spawnSync("true")),
    parentEnvironmentAbsent: process.env.COMPILED_PARENT_SENTINEL === undefined,
  };
} });\n`,
		);
		const host = pathToFileURL(path.join(artifact, "lib/core/script-run.js")).href;
		const protocol = pathToFileURL(path.join(artifact, "lib/core/ipc.js")).href;
		const entry = path.join(root, `child-${extension}.mjs`);
		await writeFile(
			entry,
			`import assert from "node:assert/strict";
import { forkedProgram, readableRoots } from ${JSON.stringify(host)};
import { childMessageSchema } from ${JSON.stringify(protocol)};
const child = forkedProgram({ execArgv: [${extension === "js" ? '"--no-experimental-strip-types",' : ""} "--permission", ...readableRoots(${JSON.stringify([program, path.join(root, "stage/lib"), path.join(root, "stage/kits")])}).map(dir => "--allow-fs-read="+dir), "--max-old-space-size=256"], timeoutMs: 10000 });
const complete = Promise.withResolvers();
const closed = Promise.withResolvers();
let output = "";
let replied = false;
const timer = setTimeout(() => { child.kill(); complete.reject(new Error("child did not finish: "+output)); }, 10000);
child.onOutput(chunk => { output += chunk.toString(); });
child.onError(error => complete.reject(error));
child.onClose(() => { clearTimeout(timer); closed.resolve(); if (!replied) complete.reject(new Error("child closed: "+output)); });
child.onMessage(raw => {
  try {
    const message = childMessageSchema.parse(typeof raw === "string" ? JSON.parse(raw) : raw);
    if (message.kind === "meta") child.send({kind:"meta-accepted",models:{},forcedToolChoice:false,tokens:0});
    if (message.kind === "result") { replied = true; complete.resolve(message.value); }
    if (message.kind === "failed") complete.reject(new Error(message.message+"\\n"+output));
  } catch(error) { complete.reject(error); child.kill(); }
});
child.send({kind:"job",job:{slug:"fixture",modulePath:${JSON.stringify(program)},inputs:${JSON.stringify(REQUESTED_PULL_REQUEST.inputs)},files:null,now:"2026-01-01T00:00:00Z",deadline:Date.now()+10000}});
try { assert.deepEqual(await complete.promise,{own:true,siblingDenied:true,writeDenied:true,spawnDenied:true,parentEnvironmentAbsent:true}); }
finally { if(child.connected) child.kill(); await closed.promise; }
console.log("sandbox file grants verified");\n`,
		);
		const result = runNode(entry, ["--no-experimental-strip-types"]);
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /sandbox file grants verified/u);
	});
}

registerChild("js");
registerChild("ts");
