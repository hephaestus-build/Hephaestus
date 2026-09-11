/** Proves that the native PMD task accepts clean code and rejects violations and incomplete analysis. */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join, relative, resolve } from "node:path";

import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export function pmdResult(xml: string) {
	assert.equal(SyntaxValidator.validate(xml), true, "Invalid PMD XML");
	const parsed: unknown = new XMLParser({
		ignoreAttributes: false,
		isArray: (name) => ["file", "violation", "error", "configerror"].includes(name),
	}).parse(xml);
	const report = asRecord(asRecord(parsed, "PMD XML").pmd, "PMD report");
	const violations = asArray(report.file ?? [], "PMD files").flatMap((file) =>
		asArray(asRecord(file, "PMD file").violation ?? [], "PMD violations").map((violation) =>
			asString(asRecord(violation, "PMD violation")["@_rule"], "PMD rule"),
		),
	);
	return {
		violations,
		errors:
			asArray(report.error ?? [], "PMD errors").length +
			asArray(report.configerror ?? [], "PMD configuration errors").length,
	};
}

async function main(): Promise<void> {
	const root = resolve(import.meta.dirname, "..");
	await mkdir(join(root, "tmp"), { recursive: true });
	const directory = await mkdtemp(join(root, "tmp", "pmd-"));
	const initScript = join(directory, "init.gradle.kts");
	const report = join(directory, "report.xml");
	try {
		// Exercise the real task and its annotation policy without changing or compiling production sources.
		await writeFile(
			initScript,
			`import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.tasks.SourceSetContainer

gradle.projectsEvaluated {
    val application = rootProject.project(":application")
    val directory = File(application.providers.environmentVariable("HEPHAESTUS_PMD_CANARY_DIRECTORY").get())
    val mainSources = application.extensions.getByType<SourceSetContainer>().named("main").get().allJava.files
    application.tasks.named<Pmd>("pmdMain") {
        check(mainSources.isNotEmpty() && source.files.containsAll(mainSources)) {
            "PMD must select every production Java source."
        }
        val analysisClasspath = classpath?.files.orEmpty()
        check(analysisClasspath.contains(application.layout.buildDirectory.dir("classes/java/main").get().asFile)) {
            "PMD must retain compiled application types on its analysis classpath."
        }
        check(analysisClasspath.contains(javaLauncher.get().metadata.installationPath.file("lib/jrt-fs.jar").asFile)) {
            "PMD must resolve platform types against its analysis toolchain."
        }
        setSource(application.files(File(directory, "Canary.java")))
        classpath = application.files(javaLauncher.map { it.metadata.installationPath.file("lib/jrt-fs.jar") })
        reports.xml.outputLocation.set(File(directory, "report.xml"))
        reports.html.required.set(false)
    }
}
`,
		);
		for (const scenario of [
			{
				name: "clean source",
				source:
					"@org.springframework.boot.autoconfigure.SpringBootApplication public class Canary {}",
				violations: [],
				errors: 0,
			},
			{
				name: "unused field",
				source:
					"@org.springframework.boot.autoconfigure.SpringBootApplication public class Canary { private int deliberatelyUnused; }",
				violations: ["UnusedPrivateField"],
				errors: 0,
			},
			{
				name: "ignored stream skip result",
				source:
					"public class Canary { public void skip(java.io.InputStream stream) throws java.io.IOException { stream.skip(1); } }",
				violations: ["UnusedReturnValue"],
				errors: 0,
			},
			{
				name: "analysis error",
				source: "public class Canary { this is not valid Java }",
				violations: [],
				errors: 1,
			},
		]) {
			await writeFile(join(directory, "Canary.java"), scenario.source);
			await rm(report, { force: true });
			const result = spawnSync(
				process.execPath,
				[
					resolve(import.meta.dirname, "run-gradlew.ts"),
					"--init-script",
					// A relative argument stays space-free even when the Windows checkout path is not.
					relative(resolve(root, "server"), initScript),
					":application:pmdMain",
					"--console=plain",
				],
				{
					cwd: root,
					encoding: "utf8",
					maxBuffer: CAPTURE_LIMIT_BYTES,
					env: { ...process.env, HEPHAESTUS_PMD_CANARY_DIRECTORY: directory },
				},
			);
			try {
				assert.ifError(result.error);
				assert.equal(result.signal, null, "PMD was interrupted");
				assert.deepEqual(pmdResult(await readFile(report, "utf8")), {
					violations: scenario.violations,
					errors: scenario.errors,
				});
				assert.equal(result.status === 0, scenario.violations.length + scenario.errors === 0);
			} catch (error) {
				console.error(`PMD canary failed for ${scenario.name}:\n${result.stdout}${result.stderr}`);
				throw error;
			}
		}
		console.log("check-pmd-canary: clean source passed; a violation and an analysis error failed.");
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

if (import.meta.main) await main();
