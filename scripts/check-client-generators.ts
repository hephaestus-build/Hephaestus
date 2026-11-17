/**
 * Generates a Swift client and a Kotlin client from the unmodified `server/openapi.yaml`, compiles
 * both, and decodes the response fixtures with each. Two strict generators stand in for the clients
 * of other languages (ADR 0103 rules 2, 3 and 5). docs/contributor/ci-cd.mdx § API compatibility
 * owns the rules.
 *
 * Needs Docker, and the JDK of `.java-version` at `JAVA_HOME` or on `PATH`. Gradle reads `GRADLE_USER_HOME`.
 *
 * Usage: vp run gate:client-generators
 */
import { spawn } from "node:child_process";
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir, userInfo } from "node:os";
import path from "node:path";

import { parse } from "yaml";

import {
	PUBLIC_OPERATIONS,
	SPEC,
	readPublicOperations,
	walkPublicResponses,
} from "./check-api-compatibility.ts";
import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { resolveCiTool } from "./lib/ci-tool-resolver.ts";
import { toolCache } from "./lib/pinned-binary.ts";
import { exitStatus } from "./lib/process.ts";

const ROOT = path.resolve(import.meta.dirname, "..");
const TEMPLATES = path.join(import.meta.dirname, "client-generators");
/** The index digest of the official Swift image. Its toolchain builds the generator and the client. */
const SWIFT_IMAGE =
	"swift:6.2.4@sha256:3459518b2e723a7c363d0f8cfa44fc796c77ecd8c18e92fd10e6f92dd78c0b12";
/** swift-openapi-generator prints its configuration each time it runs, and this line first. */
const SWIFT_GENERATOR_RAN = "- OpenAPI document path:";
/**
 * The configuration of the Kotlin client: kotlinx.serialization keeps an unknown enum value, and a
 * free-form schema stays JSON instead of `Any`, which kotlinx.serialization cannot decode.
 */
const KOTLIN_GENERATOR_OPTIONS = [
	"--library",
	"jvm-okhttp4",
	"--additional-properties",
	"serializationLibrary=kotlinx_serialization,enumUnknownDefaultCase=true,packageName=build.hephaestus.api",
	"--type-mappings",
	"AnyType=JsonElement,object=JsonObject",
	"--import-mappings",
	"JsonElement=kotlinx.serialization.json.JsonElement,JsonObject=kotlinx.serialization.json.JsonObject",
	"--global-property",
	"apiDocs=false,modelDocs=false,apiTests=false,modelTests=false",
];

export interface SkippedSchema {
	/** The JSON pointer of the skipped schema, such as `#/components/schemas/ItemRef/provider`. */
	readonly pointer: string;
	readonly message: string;
}

/**
 * Each distinct `skipping` warning of swift-openapi-generator. A warning can start in the middle of a
 * line, and the generator can repeat one.
 */
export function skippedSchemas(log: string): SkippedSchema[] {
	const skips = new Map<string, SkippedSchema>();
	for (const { groups } of log.matchAll(
		/warning: (?<message>[^\n]*?skipping[^\n]*?) ?\[context: foundIn=[^\n(]*\((?<pointer>#\/[^)\n]*)\)(?<rest>[^\]\n]*)\]/gu,
	)) {
		const skip = {
			pointer: `${groups?.pointer ?? ""}${groups?.rest ?? ""}`,
			message: groups?.message ?? "",
		};
		skips.set(`${skip.pointer} ${skip.message}`, skip);
	}
	return [...skips.values()];
}

const HTTP_METHODS = new Set(["GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE"]);

/**
 * Splits the skips into those that a public response reaches, which drop a value from a client of
 * the public contract, and the others. A skip names a component schema or a part of an operation.
 */
export function classifySkips(
	skips: readonly SkippedSchema[],
	spec: unknown,
	publicOperations: ReadonlySet<string>,
): { publicSkips: SkippedSchema[]; otherSkips: SkippedSchema[] } {
	const reached = new Set<string>();
	walkPublicResponses(spec, publicOperations, (node) => {
		const ref = node.$ref;
		if (typeof ref === "string" && ref.startsWith("#/components/schemas/")) {
			reached.add(ref.slice("#/components/schemas/".length));
		}
	});
	const isPublic = ({ pointer }: SkippedSchema) => {
		const schema = /^#\/components\/schemas\/(?<name>[^/]+)/u.exec(pointer)?.groups?.name;
		if (schema !== undefined) {
			return reached.has(schema);
		}
		// swift-openapi-generator writes `#/paths/<path>/<METHOD>/...` without escaping the path.
		const segments = pointer.split("/");
		const index = segments.findIndex((segment) => HTTP_METHODS.has(segment));
		const method = segments[index];
		return (
			pointer.startsWith("#/paths/") &&
			index > 1 &&
			method !== undefined &&
			segments[index + 1] === "responses" &&
			publicOperations.has(`${method} /${segments.slice(2, index).join("/")}`)
		);
	};
	return {
		publicSkips: skips.filter((skip) => isPublic(skip)),
		otherSkips: skips.filter((skip) => !isPublic(skip)),
	};
}

/** Runs a command, shows its output as it comes, and returns the output with its exit status. */
async function runLogged(
	command: string,
	args: readonly string[],
	options: { cwd?: string; env?: Record<string, string | undefined> } = {},
): Promise<{ ok: boolean; status: string; log: string }> {
	const child = spawn(command, args, {
		cwd: options.cwd,
		env: { ...process.env, ...options.env },
		stdio: ["ignore", "pipe", "pipe"],
	});
	const chunks: string[] = [];
	const collect = (chunk: Buffer) => {
		chunks.push(chunk.toString("utf8"));
		process.stdout.write(chunk);
	};
	child.stdout.on("data", collect);
	child.stderr.on("data", collect);
	const exited = Promise.withResolvers<{ ok: boolean; status: string }>();
	child.once("error", exited.reject);
	child.once("close", (code, signal) => {
		exited.resolve({ ok: code === 0, status: exitStatus(code, signal) });
	});
	return { ...(await exited.promise), log: chunks.join("") };
}

/** Builds the Swift client, runs its decode tests, and returns the generator's log. */
async function swiftRoundTrip(work: string, cache: string): Promise<string> {
	const pkg = path.join(work, "swift");
	await cp(path.join(TEMPLATES, "swift"), pkg, { recursive: true });
	await cp(path.join(ROOT, SPEC), path.join(pkg, "Sources", "HephaestusAPI", "openapi.yaml"));
	await cp(
		path.join(TEMPLATES, "fixtures"),
		path.join(pkg, "Tests", "HephaestusAPITests", "Fixtures"),
		{ recursive: true },
	);
	const scratch = path.join(cache, "swift");
	await mkdir(scratch, { recursive: true });
	const { uid, gid } = userInfo();
	const { ok, status, log } = await runLogged("docker", [
		"run",
		"--rm",
		"--user",
		`${uid}:${gid}`,
		"--env",
		"HOME=/scratch/home",
		"--volume",
		`${pkg}:/pkg`,
		"--volume",
		`${scratch}:/scratch`,
		"--workdir",
		"/pkg",
		SWIFT_IMAGE,
		"swift",
		"test",
		"--scratch-path",
		"/scratch/build",
		"--force-resolved-versions",
	]);
	if (!ok) {
		throw new Error(`The Swift client failed to build or decode, swift exited with ${status}`);
	}
	if (!log.includes(SWIFT_GENERATOR_RAN)) {
		throw new Error("swift-openapi-generator did not run, so its warnings are unknown");
	}
	return log;
}

async function kotlinGenerator(): Promise<string> {
	const pin = await ciToolPin("openapi-generator-cli");
	const resolved = await resolveCiTool(pin);
	if (resolved.cacheable) {
		return resolved.file;
	}
	const directory = await mkdtemp(path.join(tmpdir(), "openapi-generator-"));
	const jar = path.join(directory, pin.name);
	await writeFile(jar, resolved.bytes);
	return jar;
}

/** Generates the Kotlin client, then compiles it and runs its decode tests with the server's Gradle. */
async function kotlinRoundTrip(work: string): Promise<void> {
	const project = path.join(work, "kotlin");
	await cp(path.join(TEMPLATES, "kotlin"), project, { recursive: true });
	await cp(
		path.join(TEMPLATES, "fixtures"),
		path.join(project, "src", "test", "resources", "fixtures"),
		{ recursive: true },
	);
	const java =
		process.env.JAVA_HOME === undefined ? "java" : path.join(process.env.JAVA_HOME, "bin", "java");
	const generated = await runLogged(java, [
		"-jar",
		await kotlinGenerator(),
		"generate",
		"--input-spec",
		path.join(ROOT, SPEC),
		"--generator-name",
		"kotlin",
		"--output",
		path.join(project, "generated"),
		...KOTLIN_GENERATOR_OPTIONS,
	]);
	if (!generated.ok) {
		throw new Error(`OpenAPI Generator failed on the spec, java exited with ${generated.status}`);
	}
	const built = await runLogged(path.join(ROOT, "server", "gradlew"), [
		"--project-dir",
		project,
		"--no-daemon",
		"--console=plain",
		"test",
	]);
	if (!built.ok) {
		throw new Error(
			`The Kotlin client failed to build or decode, Gradle exited with ${built.status}`,
		);
	}
}

async function main(): Promise<void> {
	const work = await mkdtemp(path.join(tmpdir(), "client-generators-"));
	const cache = process.env.CLIENT_GENERATORS_CACHE ?? toolCache("client-generators");
	try {
		const log = await swiftRoundTrip(work, cache);
		const { publicSkips, otherSkips } = classifySkips(
			skippedSchemas(log),
			parse(await readFile(path.join(ROOT, SPEC), "utf8")),
			await readPublicOperations(path.join(ROOT, PUBLIC_OPERATIONS)),
		);
		console.log(
			`swift-openapi-generator skipped ${otherSkips.length} schemas that no public response reaches:`,
		);
		console.log(otherSkips.map(({ pointer }) => `  ${pointer}`).join("\n"));
		for (const { pointer, message } of publicSkips) {
			console.log(
				`::error title=Client generators::swift-openapi-generator drops ${pointer}, which a public response reaches (ADR 0103 rule 2): ${message}`,
			);
		}
		await kotlinRoundTrip(work);
		if (publicSkips.length > 0) {
			process.exitCode = 1;
		}
	} finally {
		await rm(work, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	}
}
