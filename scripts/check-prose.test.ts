import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { readFile, mkdtemp, rm, writeFile, mkdir, readdir, stat } from "node:fs/promises";
import { createServer, type ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { zipSync } from "fflate";

import {
	assertGrowth,
	parsePaths,
	checkRatchet,
	issueFormProse,
	skillMetadataAlerts,
	issueFormAlerts,
} from "./check-prose.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { output } from "./lib/process.ts";
import {
	approvedWords,
	contractions,
	productTerms,
	technicalNames,
	wordAlerts,
} from "./lib/ste-words.ts";
import { uiAlerts, uiRuleName } from "./lib/ui-text.ts";
import {
	assetFor,
	installValeBinary,
	executableFromArchive,
	prepareVale,
	parseValeAlerts,
	valeAlerts,
	verifyArchive,
} from "./lib/vale.ts";

await test("STE paths are explicit, unique, and grow only", () => {
	assert.deepEqual(parsePaths('["docs/user/test.mdx"]'), ["docs/user/test.mdx"]);
	assert.deepEqual(
		parsePaths('[".github/ISSUE_TEMPLATE/bug.yml", ".github/ISSUE_TEMPLATE/config.yaml"]'),
		[".github/ISSUE_TEMPLATE/bug.yml", ".github/ISSUE_TEMPLATE/config.yaml"],
	);
	for (const source of [
		"[]",
		'["../test.md"]',
		'["/test.md"]',
		'["docs/**"]',
		'["a.md", "a.md"]',
		'["server/data.json"]',
		'["server/application.yml"]',
		'[".github/workflows/check.yml"]',
		'[".github/ISSUE_TEMPLATE/nested/form.yml"]',
		'["webapp/src/components/Test.tsx"]',
	]) {
		assert.throws(() => parsePaths(source));
	}
	assert.deepEqual(
		parsePaths(
			'[".github/DISCUSSION_TEMPLATE/ideas.yml", ".claude/skills/composition-patterns/metadata.json"]',
		),
		[".github/DISCUSSION_TEMPLATE/ideas.yml", ".claude/skills/composition-patterns/metadata.json"],
	);
	assert.throws(() => parsePaths('[".claude/skills/composition-patterns/other.json"]'));
	assert.doesNotThrow(() => assertGrowth(["a.md"], ["a.md", "b.md"]));
	assert.throws(() => assertGrowth(["a.md"], ["b.md"]), /Restore them/u);
});

await test("the shared words preserve boundaries, case, and technical names", () => {
	assert.equal(approvedWords.includes("use"), true);
	assert.equal(productTerms.includes("practice feedback"), true);
	assert.deepEqual(technicalNames("| **Example name** | A meaning |\n| **Deploy** | An action |"), [
		"example name",
		"deploy",
	]);
	assert.deepEqual(
		wordAlerts("Utilize it PRIOR\nTO use.").map(({ to }) => to),
		["use", "before"],
	);
	assert.deepEqual(wordAlerts("utilizedx utilisation"), []);
	assert.deepEqual(wordAlerts("Writing standard"), []);
	assert.ok(contractions.length > 0);
	assert.deepEqual(
		wordAlerts("Don't stop it.").map(({ to }) => to),
		["do not"],
	);
	assert.deepEqual(
		wordAlerts("You're set. Don't stop it. It won’t run.", "voice").map(({ to }) => to),
		["do not", "will not"],
	);
});

await test("Vale pins cover every supported OS and reject changed archives", () => {
	for (const platform of ["linux", "darwin", "win32"]) {
		for (const arch of ["x64", "arm64"]) {
			assert.match(assetFor(platform, arch).sha256, /^[a-f0-9]{64}$/u);
		}
	}
	assert.throws(() => assetFor("linux", "ia32"));
	const bytes = new Uint8Array([1, 2, 3]);
	verifyArchive(bytes, createHash("sha256").update(bytes).digest("hex"));
	assert.throws(() => verifyArchive(bytes, "0".repeat(64)), /checksum/u);
	assert.deepEqual(
		executableFromArchive(zipSync({ "vale.exe": bytes, "../escape": bytes }), true),
		bytes,
	);
	assert.throws(() => executableFromArchive(zipSync({ other: bytes }), true), /no vale.exe/u);
});

await test("concurrent Vale installers publish one verified binary and reuse it offline", async () => {
	const cache = await mkdtemp(path.join(tmpdir(), "vale-cache-test-"));
	const executable = new Uint8Array([1, 2, 3]);
	const archive = zipSync({ "vale.exe": executable });
	const asset = {
		name: "vale.zip",
		sha256: createHash("sha256").update(archive).digest("hex"),
	};
	const workers = 4;
	let downloads = 0;
	const responses: ServerResponse[] = [];
	const server = createServer((_request, response) => {
		downloads += 1;
		if (downloads > workers) {
			response.end(archive);
			return;
		}
		responses.push(response);
		// Hold every download until all processes have observed the empty cache.
		if (responses.length === workers) {
			for (const pending of responses) {
				pending.end(archive);
			}
		}
	});
	try {
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		const options = {
			cache,
			version: "test",
			asset,
			url: `http://127.0.0.1:${address.port}/`,
			windows: true,
		};
		const source = `
			import { installValeBinary } from ${JSON.stringify(new URL("lib/vale.ts", import.meta.url).href)};
			const [cache, version, name, sha256, url] = process.argv.slice(1);
			console.log(await installValeBinary({ cache, version, asset: { name, sha256 }, url, windows: true }));
		`;
		const results = await Promise.all(
			Array.from({ length: workers }, async () =>
				output(
					process.execPath,
					[
						"--input-type=module",
						"-e",
						source,
						cache,
						options.version,
						asset.name,
						asset.sha256,
						options.url,
					],
					{ signal: AbortSignal.timeout(30_000) },
				),
			),
		);
		const binary = path.join(cache, `vale-test-${asset.sha256}`, "vale.exe");
		assert.deepEqual(
			results.map((stdout) => stdout.trim()),
			Array.from({ length: workers }, () => binary),
		);
		assert.equal(downloads, workers);
		const other = await installValeBinary({ ...options, version: "other" });
		assert.equal(other, path.join(cache, `vale-other-${asset.sha256}`, "vale.exe"));
		await assert.rejects(
			installValeBinary({ ...options, asset: { ...asset, sha256: "0".repeat(64) } }),
			/checksum/u,
		);
		const closed = once(server, "close");
		server.close();
		await closed;
		assert.deepEqual(await readFile(binary), Buffer.from(executable));
		const directories = [
			path.basename(path.dirname(other)),
			path.basename(path.dirname(binary)),
		].toSorted();
		const entries = await readdir(cache);
		assert.deepEqual(entries.toSorted(), directories);
		const before = await stat(binary);
		assert.equal(await installValeBinary(options), binary);
		const after = await stat(binary);
		assert.equal(after.ino, before.ino);
		assert.equal(after.mtimeMs, before.mtimeMs);
		assert.equal(after.ctimeMs, before.ctimeMs);

		await writeFile(binary, "changed executable");
		await assert.rejects(installValeBinary(options), /executable differs/u);
		await writeFile(binary, executable);
		await writeFile(path.join(path.dirname(binary), asset.name), "changed archive");
		await assert.rejects(installValeBinary(options), /checksum/u);
	} finally {
		server.closeAllConnections();
		server.close();
		await rm(cache, { recursive: true, force: true });
	}
});

await test("Vale runs share the binary but remove only their own configuration", async () => {
	const [first, second] = await Promise.all([prepareVale(), prepareVale()]);
	try {
		assert.equal(first.binary, second.binary);
		assert.notEqual(first.config, second.config);
		assert.notEqual(path.dirname(first.config), path.dirname(first.binary));
		assert.deepEqual(valeAlerts(first, [".vale/fixtures/Words-good.md"]), new Map());
		await first.dispose();
		await assert.rejects(readFile(first.config), { code: "ENOENT" });
		const bytes = await readFile(first.binary);
		assert.ok(bytes.length > 0);
		assert.deepEqual(valeAlerts(second, [".vale/fixtures/Words-good.md"]), new Map());
	} finally {
		await first.dispose();
		await second.dispose();
	}
});

await test("Vale output uses repository path separators on every OS", () => {
	const alert = { Check: "STE.Words", Severity: "error", Message: "Write use.", Line: 1 };
	for (const filename of [".vale/fixtures/Words-bad.md", String.raw`.vale\fixtures\Words-bad.md`]) {
		assert.deepEqual(
			parseValeAlerts(JSON.stringify({ [filename]: [alert] })).get(".vale/fixtures/Words-bad.md"),
			[alert],
		);
	}
});

await test("the path ratchet compares the actual base and rejects shrinkage, missing files, and a missing base", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "ste-ratchet-"));
	try {
		const env = environmentForGitFixture();
		const git = (...args: string[]) =>
			execFileSync("git", args, { cwd: directory, env, stdio: "pipe" });
		git("init", "--quiet");
		await mkdir(path.join(directory, ".vale"));
		await writeFile(path.join(directory, "a.md"), "Use it.");
		await writeFile(path.join(directory, "b.md"), "Use it.");
		const manifest = path.join(directory, ".vale", "enforced-paths.json");
		await writeFile(manifest, '["a.md"]');
		git("add", ".");
		git(
			"-c",
			"user.name=Fixture",
			"-c",
			"user.email=fixture@example.invalid",
			"commit",
			"--quiet",
			"-m",
			"Initial paths",
		);
		await writeFile(manifest, '["a.md", "b.md"]');
		assert.deepEqual(checkRatchet("HEAD", directory), ["a.md", "b.md"]);
		await writeFile(manifest, '["b.md"]');
		assert.throws(() => checkRatchet("HEAD", directory), /lost paths/u);
		await writeFile(manifest, '["a.md", "missing.md"]');
		assert.throws(() => checkRatchet("HEAD", directory), /ENOENT/u);
		assert.throws(() => checkRatchet("missing", directory));
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("each Vale rule has a passing sample, a failing sample, and a repair message", async () => {
	const vale = await prepareVale();
	try {
		const rules = [
			"SentenceLength",
			"ProcedureLength",
			"ParagraphLength",
			"Words",
			"Contractions",
			"Semicolons",
			"PassiveVoice",
			"IngForms",
			"Vocabulary",
		];
		for (const rule of rules) {
			const good = `.vale/fixtures/${rule}-good.md`;
			const bad = `.vale/fixtures/${rule}-bad.md`;
			assert.equal(
				(valeAlerts(vale, [good]).get(good) ?? []).some((alert) => alert.Check === `STE.${rule}`),
				false,
				`${rule}: good sample`,
			);
			const alerts = valeAlerts(vale, [bad]).get(bad) ?? [];
			const alert = alerts.find((item) => item.Check === `STE.${rule}`);
			assert.ok(alert, `${rule}: bad sample`);
			assert.match(alert.Message, /Write|write|Use|use|Name|Split/u);
			assert.equal(
				alert.Severity,
				["PassiveVoice", "IngForms", "Vocabulary"].includes(rule) ? "suggestion" : "error",
			);
		}
		assert.deepEqual(
			[...valeAlerts(vale, [".vale/fixtures/markup-good.mdx"], "error").values()].flat(),
			[],
		);
		assert.ok(
			[...valeAlerts(vale, [".vale/fixtures/markup-bad.mdx"], "error").values()]
				.flat()
				.some((alert) => alert.Check === "STE.Words"),
		);
		assert.equal(
			[...valeAlerts(vale, [".vale/fixtures/markup-bad.mdx"], "error").values()]
				.flat()
				.filter((alert) => alert.Check === "STE.Words").length,
			4,
		);
		const license = await readFile(".vale/OpenSTE-LICENSE.txt", "utf8");
		assert.match(license, /MIT License/u);
		assert.match(license, /Copyright \(c\) 2026 openSTE.org/u);
	} finally {
		await vale.dispose();
	}
});

await test("user docs allow positive contractions and spell out negative ones", async () => {
	const vale = await prepareVale();
	try {
		const checks = (file: string) =>
			(valeAlerts(vale, [file], "error").get(file) ?? []).map(({ Check }) => Check);
		assert.deepEqual(checks(".vale/fixtures/docs/user/NegativeContractions-good.md"), []);
		assert.deepEqual(checks(".vale/fixtures/docs/user/NegativeContractions-bad.md"), [
			"STE.NegativeContractions",
		]);
		assert.deepEqual(checks(".vale/fixtures/Contractions-bad.md"), ["STE.Contractions"]);
	} finally {
		await vale.dispose();
	}
});

await test("the UI report names the check behind each rule message", () => {
	assert.equal(
		uiRuleName("Split this sentence. Write no more than 25 words per sentence."),
		"STE.SentenceLength",
	);
	assert.equal(uiRuleName("Write two sentences instead of a semicolon."), "STE.Semicolons");
	assert.equal(uiRuleName('Write the apostrophe in "it\'s" as ’.'), "UI.Apostrophe");
	assert.equal(
		uiRuleName('Write "do not" instead of "don’t". Keep the same meaning.'),
		"STE.NegativeContractions",
	);
	assert.equal(uiRuleName('Write "use" instead of "utilize". Keep the same meaning.'), "STE.Words");
});

await test("the UI report uses the registered oxlint rule and skips machine props and tests", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "ui-text-fixture-"));
	try {
		const file = path.join(directory, "sample.tsx");
		const skipped = path.join(directory, "sample.test.tsx");
		await writeFile(file, '<p title="Utilize it" className="ensure">Use it.</p>');
		await writeFile(skipped, "<p>Utilize it.</p>");
		const alerts = await uiAlerts([file, skipped]);
		assert.equal(alerts.length, 1);
		assert.match(alerts[0]?.message ?? "", /Write "use" instead of "utilize"/u);
		assert.match(alerts[0]?.filename ?? "", /sample\.tsx$/u);
		await writeFile(file, "<p>");
		await assert.rejects(uiAlerts([file]));
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("issue forms select prose fields and ignore configuration values", async () => {
	const fields = issueFormProse(await readFile(".vale/fixtures/issue-form-prose-good.yml", "utf8"));
	assert.deepEqual(
		fields.map(({ field }) => field),
		[
			"name",
			"description",
			"body[0].attributes.value",
			"body[1].attributes.label",
			"body[1].attributes.description",
			"body[1].attributes.placeholder",
			"body[2].attributes.options[0].label",
			"contact_links[0].name",
		],
	);
	assert.deepEqual(issueFormProse("blank_issues_enabled: false"), []);
	assert.equal(fields[2]?.text, "Check the issue list.\nDo not include private data.\n");
	assert.throws(() => issueFormProse("name: Use it.\nname: Use it."), /unique/u);
	for (const source of [
		"- name: Use it.",
		"name: 123",
		"description: null",
		"body: wrong",
		"body: [123]",
		"body: [{attributes: 123}]",
		"body: [{type: checkboxes, attributes: {options: wrong}}]",
		"body: [{type: checkboxes, attributes: {options: [123]}}]",
		"body: [{type: checkboxes, attributes: {options: [{label: 123}]}}]",
		"contact_links: wrong",
	]) {
		assert.throws(() => issueFormProse(source));
	}
	assert.deepEqual(issueFormProse("name: &label Use it.\ndescription: *label"), [
		{ field: "name", text: "Use it." },
		{ field: "description", text: "Use it." },
	]);
});

await test("issue-form fixtures check each prose field through Vale", async () => {
	const vale = await prepareVale();
	try {
		assert.deepEqual(issueFormAlerts(vale, ".vale/fixtures/issue-form-prose-good.yml"), []);
		const alerts = issueFormAlerts(vale, ".vale/fixtures/issue-form-prose-bad.yml");
		assert.equal(alerts.length, 8);
		assert.ok(
			alerts.every(({ alert }) => alert.Check === "STE.Words" && alert.Severity === "error"),
		);
		assert.deepEqual(
			alerts.map(({ field }) => field),
			[
				"name",
				"description",
				"body[0].attributes.value",
				"body[1].attributes.label",
				"body[1].attributes.description",
				"body[1].attributes.placeholder",
				"body[2].attributes.options[0].label",
				"contact_links[0].name",
			],
		);
		assert.equal(alerts.find(({ field }) => field === "body[0].attributes.value")?.alert.Line, 2);
	} finally {
		await vale.dispose();
	}
});

await test("skill metadata checks its abstract and ignores machine fields", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "ste-skill-metadata-"));
	const vale = await prepareVale();
	try {
		const file = path.join(directory, "metadata.json");
		await writeFile(
			file,
			JSON.stringify({
				abstract: "Use it.",
				version: "utilize; don't",
				references: ["utilize; don't"],
			}),
		);
		assert.deepEqual(skillMetadataAlerts(vale, file), []);
		await writeFile(file, JSON.stringify({ abstract: "Utilize it." }));
		const alerts = skillMetadataAlerts(vale, file);
		assert.equal(alerts.length, 1);
		assert.equal(alerts[0]?.field, "abstract");
		assert.equal(alerts[0].alert.Check, "STE.Words");
		await writeFile(file, JSON.stringify({ abstract: 123 }));
		assert.throws(() => skillMetadataAlerts(vale, file), /abstract/u);
	} finally {
		await vale.dispose();
		await rm(directory, { recursive: true, force: true });
	}
});

await test("an exact-source exception affects only its named rule and span", async () => {
	const vale = await prepareVale();
	try {
		const alerts = valeAlerts(vale, [".vale/fixtures/quoted-source-scope.md"], "error");
		const list = [...alerts.values()].flat();
		assert.equal(list.filter(({ Check }) => Check === "STE.Contractions").length, 1);
		assert.equal(list.find(({ Check }) => Check === "STE.Contractions")?.Line, 11);
		assert.equal(list.filter(({ Check }) => Check === "STE.Words").length, 1);
		assert.equal(list.find(({ Check }) => Check === "STE.Words")?.Line, 7);
	} finally {
		await vale.dispose();
	}
});
