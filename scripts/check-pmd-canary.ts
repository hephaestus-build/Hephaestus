/** Proves that the native PMD task accepts clean code and rejects violations and incomplete analysis. */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";

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
	const root = path.resolve(import.meta.dirname, "..");
	await mkdir(path.join(root, "tmp"), { recursive: true });
	const directory = await mkdtemp(path.join(root, "tmp", "pmd-"));
	const initScript = path.join(directory, "init.gradle.kts");
	const report = path.join(directory, "report.xml");
	try {
		// Exercise the real task without changing or compiling production sources.
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
        reports.xml.outputLocation.set(File(directory, "report.xml"))
        reports.html.required.set(false)
    }
}
`,
		);
		for (const scenario of [
			{
				name: "clean source",
				source: "public class Canary {}",
				violations: [],
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
				name: "parameterized and constant log messages",
				source: `public class Canary {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Canary.class);
    public void log(String value) {
        logger.info("Value {}", value);
        logger.info(org.slf4j.MarkerFactory.getMarker("safe"), "Value {}", value);
        logger.info("Value {}", "prefix-" + value);
        logger.info("Constant " + "message");
        logger.atInfo().addKeyValue("value", value).log("Constant message");
        java.util.logging.Logger.getLogger("canary").info("Other logger " + value);
    }
}`,
				violations: [],
				errors: 0,
			},
			{
				name: "concatenated classic and fluent log messages",
				source: `public class Canary {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Canary.class);
    public void log(String value) {
        logger.info("Value " + value);
        logger.atInfo().log("Value " + value);
        logger.atInfo().setMessage("Value " + value).log();
        logger.info(org.slf4j.MarkerFactory.getMarker("safe"), "Value " + value);
    }
}`,
				violations: [
					"ParameterizedLogMessage",
					"ParameterizedLogMessage",
					"ParameterizedLogMessage",
					"ParameterizedLogMessage",
				],
				errors: 0,
			},
			{
				name: "private arguments in classic, fluent, and retained builders",
				source: `public class Canary {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Canary.class);
    private static org.slf4j.spi.LoggingEventBuilder event() { return logger.atWarn(); }
    public void log(String accountLogin, String workspaceSlug,
            de.tum.cit.aet.hephaestus.workspace.Workspace workspace,
            de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession session) {
        logger.info("Scope {}", workspace.getAccountLogin());
        logger.info("Scope {}", session.accountLogin());
        logger.info("Scope {}", session.slug());
        logger.atInfo().addKeyValue("scope", accountLogin).log("Started");
        var builder = logger.atLevel(org.slf4j.event.Level.WARN);
        builder.addArgument(workspaceSlug).log("Scope {}");
        event().addKeyValue("scope", workspace.getAccountLogin()).log("Started");
    }
}`,
				violations: Array.from({ length: 6 }, () => "NoPersonalLogArgument"),
				errors: 0,
			},
			{
				name: "private context, optional login, and misleading organization names",
				source: `public class Canary {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Canary.class);
    public void log(de.tum.cit.aet.hephaestus.integration.scm.domain.user.User orgMember,
            String organizationMemberLogin, String safeSlug,
            de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext context) {
        logger.info("Member {}", orgMember.getLogin());
        logger.info("Member {}", organizationMemberLogin);
        logger.info("Scope {}", context.slug());
        logger.info("Actor {}", de.tum.cit.aet.hephaestus.core.security.SecurityUtils.getCurrentUserLogin().orElse("system"));
        logger.info("Scope {}", safeSlug);
    }
}`,
				violations: Array.from({ length: 5 }, () => "NoPersonalLogArgument"),
				errors: 0,
			},
			{
				name: "private arguments through Lombok's logger",
				source: `@lombok.extern.slf4j.Slf4j
public class Canary {
    public void log(String accountLogin) { log.info("Scope {}", accountLogin); }
}`,
				violations: ["NoPersonalLogArgument"],
				errors: 0,
			},
			// Each class that a class-wide exemption once covered: its own name and package, with the call it used to make.
			{
				name: "a login in the public contributor list",
				source: `package de.tum.cit.aet.hephaestus.contributors;
public class ContributorService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContributorService.class);
    public void skip(String login) { log.debug("Skipping excluded contributor: {}", login); }
}`,
				violations: ["NoPersonalLogArgument"],
				errors: 0,
			},
			{
				name: "an organization login",
				source: `package de.tum.cit.aet.hephaestus.integration.scm.github.organization;
public class GitHubOrganizationProcessor {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GitHubOrganizationProcessor.class);
    public void created(de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization saved) {
        log.debug("Created organization: orgId={}, orgLogin={}", saved.getNativeId(), saved.getLogin());
    }
}`,
				violations: ["NoPersonalLogArgument"],
				errors: 0,
			},
			{
				name: "a dev fixture login",
				source: `package de.tum.cit.aet.hephaestus.core.auth.dev;
public class DevLoginService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DevLoginService.class);
    public void signedIn(long id, String username, boolean admin) {
        log.info("auth.dev-login: signed in dev account id={} login={} admin={}", id, username, admin);
    }
}`,
				violations: ["NoPersonalLogArgument"],
				errors: 0,
			},
			{
				name: "analysis error",
				source: "public class Canary { this is not valid Java }",
				violations: [],
				errors: 1,
			},
		]) {
			await writeFile(path.join(directory, "Canary.java"), scenario.source);
			await rm(report, { force: true });
			const result = spawnSync(
				process.execPath,
				[
					path.resolve(import.meta.dirname, "run-gradlew.ts"),
					"--init-script",
					// A relative argument stays space-free even when the Windows checkout path is not.
					path.relative(path.resolve(root, "server"), initScript),
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

if (import.meta.main) {
	await main();
}
