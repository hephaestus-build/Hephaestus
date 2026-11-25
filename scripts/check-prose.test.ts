import assert from "node:assert/strict";
import { type ChildProcess, execFileSync, spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import {
	readFile,
	mkdtemp,
	realpath,
	rm,
	writeFile,
	mkdir,
	readdir,
	rename,
	stat,
} from "node:fs/promises";
import { createServer, type ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { createInterface } from "node:readline";
import { test } from "node:test";
import { zipSync } from "fflate";

import {
	assertGrowth,
	catalogAlerts,
	catalogProse,
	parsePaths,
	checkRatchet,
	issueFormProse,
	skillMetadataAlerts,
	issueFormAlerts,
	productCopyFindings,
	productCopyPaths,
	matchAllowedText,
} from "./check-prose.ts";
import type { ToolSource } from "./lib/ci-tool-resolver.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { executableFromArchive, toolCache, verifyArchive } from "./lib/pinned-binary.ts";
import { fetchReleaseAssetDirect } from "./lib/release-asset.ts";
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
	prepareVale,
	parseValeAlerts,
	valeAlerts,
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
		'["server/application/src/main/resources/application.properties"]',
		'["messages.properties"]',
		'[{"path":"new.md","renamedFrom":"old.md"}]',
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
	assert.deepEqual(parsePaths('["server/application/src/main/resources/messages.properties"]'), [
		"server/application/src/main/resources/messages.properties",
	]);
	assert.doesNotThrow(() => assertGrowth(["a.md"], ["a.md", "b.md"]));
	assert.throws(() => assertGrowth(["a.md"], ["b.md"]), /restore its entry/u);
	assert.doesNotThrow(() => assertGrowth(["a.md"], ["b.md"], new Map(), () => false));
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
	verifyArchive(bytes, {
		name: "vale.zip",
		sha256: createHash("sha256").update(bytes).digest("hex"),
	});
	assert.throws(
		() => verifyArchive(bytes, { name: "vale.zip", sha256: "0".repeat(64) }),
		/checksum/u,
	);
	assert.deepEqual(
		executableFromArchive(
			zipSync({ "vale.exe": bytes, "../escape": bytes }),
			"vale.zip",
			"vale.exe",
		),
		bytes,
	);
	assert.throws(
		() => executableFromArchive(zipSync({ other: bytes }), "vale.zip", "vale.exe"),
		/no vale.exe/u,
	);
});

await test("every worktree of a clone reads one pinned-tool cache, so a new worktree's push finds Vale", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "tool-cache-"));
	try {
		const env = environmentForGitFixture();
		const clone = path.join(root, "clone");
		execFileSync("git", ["init", "--quiet", clone], { env });
		execFileSync(
			"git",
			[
				"-c",
				"user.name=t",
				"-c",
				"user.email=t@example.invalid",
				"commit",
				"--quiet",
				"--allow-empty",
				"-m",
				"root",
			],
			{ cwd: clone, env },
		);
		const worktree = path.join(root, "worktree");
		execFileSync("git", ["worktree", "add", "--quiet", "--detach", worktree], { cwd: clone, env });
		const shared = toolCache("ste", clone);
		assert.equal(toolCache("ste", worktree), shared);
		assert.equal(shared, path.join(await realpath(clone), ".git", "hephaestus-tools", "ste"));
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

const fixtureSource: ToolSource = {
	name: "direct",
	read: async (subject) => fetchReleaseAssetDirect(subject.url),
};

/** An installer process: `ready` settles when it prints its first line, `finish` when it exits. */
function startInstaller(command: string, args: string[]) {
	const child = spawn(command, args, { stdio: ["pipe", "pipe", "inherit"] });
	const lines: string[] = [];
	createInterface({ input: child.stdout }).on("line", (line) => {
		lines.push(line);
	});
	// `close` follows the end of stdout, so `lines` holds every line by then.
	const closed = once(child, "close");
	const failure = (code: unknown, signal: unknown) =>
		new Error(`An installer exited (${String(code ?? signal)}): ${lines.join("\n")}`);
	// Listens from the start: an installer prints `ready` once and then waits.
	const ready = (async () => {
		const first: unknown[] | undefined = await Promise.race([
			once(child.stdout, "data").then(() => undefined),
			closed,
		]);
		if (first !== undefined) {
			throw failure(first[0], first[1]);
		}
	})();
	const finish = async () => {
		const exit: unknown[] = await closed;
		const [code, signal] = exit;
		if (code !== 0) {
			throw failure(code, signal);
		}
		return lines;
	};
	return { child, ready, finish };
}

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
	const children: ChildProcess[] = [];
	const server = createServer((request, response) => {
		// Another local process can probe a newly opened port. Only the installers' requests count.
		if (request.url !== `/${asset.name}`) {
			response.writeHead(404).end();
			return;
		}
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
		// Every source but the fixture server would reach a real registry first.
		const options = {
			cache,
			sourceCache: `${cache}-sources`,
			allowDownload: true,
			version: "test",
			asset,
			url: `http://127.0.0.1:${address.port}/`,
			windows: true,
			sources: [fixtureSource],
		};
		// Each installer loads its modules, says so, and waits until the test closes its stdin. Thus,
		// all of them check the empty cache together, however long a process takes to start.
		const source = `
			import { once } from "node:events";
			import { installValeBinary } from ${JSON.stringify(new URL("lib/vale.ts", import.meta.url).href)};
			import { fetchReleaseAssetDirect } from ${JSON.stringify(new URL("lib/release-asset.ts", import.meta.url).href)};
			const [cache, version, name, sha256, url] = process.argv.slice(1);
			console.log("ready");
			process.stdin.resume();
			await once(process.stdin, "end");
			const sources = [{ name: "direct", read: async (subject) => fetchReleaseAssetDirect(subject.url) }];
			console.log(await installValeBinary({ cache, sourceCache: cache + "-sources", version, asset: { name, sha256 }, url, windows: true, allowDownload: true, sources }));
		`;
		await assert.rejects(installValeBinary({ ...options, allowDownload: false }), /is not cached/u);
		assert.equal(downloads, 0);
		const installers = Array.from({ length: workers }, () =>
			startInstaller(process.execPath, [
				"--input-type=module",
				"-e",
				source,
				cache,
				options.version,
				asset.name,
				asset.sha256,
				options.url,
			]),
		);
		children.push(...installers.map(({ child }) => child));
		await Promise.all(installers.map(async ({ ready }) => ready));
		for (const { child } of installers) {
			child.stdin.end();
		}
		const results = await Promise.all(installers.map(async ({ finish }) => finish()));
		const binary = path.join(cache, `vale-test-${asset.sha256}`, "vale.exe");
		assert.deepEqual(
			results.map((lines) => lines.at(-1)),
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
		assert.equal(await installValeBinary({ ...options, allowDownload: false }), binary);
		const after = await stat(binary);
		assert.equal(after.ino, before.ino);
		assert.equal(after.mtimeMs, before.mtimeMs);
		assert.equal(after.ctimeMs, before.ctimeMs);

		await writeFile(binary, "changed executable");
		assert.equal(await installValeBinary(options), binary);
		assert.deepEqual(await readFile(binary), Buffer.from(executable));
		await writeFile(path.join(path.dirname(binary), asset.name), "changed archive");
		assert.equal(await installValeBinary(options), binary);
		assert.equal(
			createHash("sha256")
				.update(await readFile(path.join(path.dirname(binary), asset.name)))
				.digest("hex"),
			asset.sha256,
		);
	} finally {
		for (const child of children) {
			child.kill();
		}
		server.closeAllConnections();
		server.close();
		await rm(cache, { recursive: true, force: true });
		await rm(`${cache}-sources`, { recursive: true, force: true });
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

for (const scenario of [
	{
		name: "retains enforcement across a committed rename",
		change: "rename",
		enforced: ["new.md"],
		retained: true,
	},
	{
		name: "accepts deletion of an enforced page together with its entry",
		change: "delete",
		enforced: ["other.md"],
		retained: true,
	},
	{
		name: "rejects un-enforcing a page that still exists",
		change: "none",
		enforced: ["other.md"],
		retained: false,
	},
	{
		name: "rejects a rename to an unenforced page",
		change: "rename",
		enforced: ["other.md"],
		retained: false,
	},
] as const) {
	await test(`the path ratchet ${scenario.name}`, async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "ste-rename-"));
		try {
			const env = environmentForGitFixture();
			const git = (...args: string[]) =>
				execFileSync("git", args, { cwd: directory, env, encoding: "utf8", stdio: "pipe" });
			const commit = (message: string) => {
				git("add", ".");
				git(
					"-c",
					"user.name=Fixture",
					"-c",
					"user.email=fixture@example.invalid",
					"commit",
					"--quiet",
					"-m",
					message,
				);
			};
			git("init", "--quiet");
			await mkdir(path.join(directory, ".vale"));
			await writeFile(path.join(directory, "old.md"), "Keep this page enforced after a rename.\n");
			await writeFile(path.join(directory, "other.md"), "Another page.\n");
			const manifest = path.join(directory, ".vale", "enforced-paths.json");
			await writeFile(manifest, '["old.md"]');
			commit("Initial enforced page");
			const base = git("rev-parse", "HEAD").trim();
			if (scenario.change === "rename") {
				await rename(path.join(directory, "old.md"), path.join(directory, "new.md"));
			} else if (scenario.change === "delete") {
				await rm(path.join(directory, "old.md"));
			}
			await writeFile(manifest, JSON.stringify(scenario.enforced));
			commit("Move, delete or un-enforce the enforced page");
			if (scenario.retained) {
				assert.deepEqual(checkRatchet(base, directory), scenario.enforced);
			} else {
				assert.throws(() => checkRatchet(base, directory), /lost paths.*old\.md/u);
			}
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	});
}

await test("historical names keep their wording while other word replacements still apply", async () => {
	const vale = await prepareVale();
	try {
		const filename = ".vale/fixtures/docs/decisions/historical-name.md";
		const alerts = (valeAlerts(vale, [filename], "error").get(filename) ?? []).filter(
			(alert) => alert.Check === "STE.Words",
		);
		assert.equal(alerts.length, 1);
		assert.equal(alerts[0]?.Line, 2);
	} finally {
		await vale.dispose();
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
			"Timeless",
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
				["PassiveVoice", "IngForms", "Vocabulary", "Timeless"].includes(rule)
					? "suggestion"
					: "error",
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

await test("user docs allow positive contractions and refuse negative ones, apologies and exclamations", async () => {
	const vale = await prepareVale();
	try {
		const checks = (file: string) =>
			(valeAlerts(vale, [file], "error").get(file) ?? []).map(({ Check }) => Check);
		assert.deepEqual(checks(".vale/fixtures/docs/user/NegativeContractions-good.md"), []);
		assert.deepEqual(checks(".vale/fixtures/docs/user/NegativeContractions-bad.md"), [
			"STE.NegativeContractions",
		]);
		assert.deepEqual(checks(".vale/fixtures/Contractions-bad.md"), ["STE.Contractions"]);
		assert.deepEqual(checks(".vale/fixtures/docs/user/ProductVoice-good.md"), []);
		assert.deepEqual(checks(".vale/fixtures/docs/user/ProductVoice-bad.md"), [
			"STE.Exclamation",
			"STE.ProductVoice",
			"STE.Please",
			"STE.Exclamation",
		]);
	} finally {
		await vale.dispose();
	}
});

await test("catalog messages use the product voice, and only a support reply may say please", async () => {
	assert.deepEqual(catalogProse("# note\n! note\n\na.b = Saved\nc:Done\n"), [
		{ field: "a.b", text: "Saved" },
		{ field: "c", text: "Done" },
	]);
	assert.deepEqual(catalogProse("a=one \\\n   two\nb=end\\"), [
		{ field: "a", text: "one two" },
		{ field: "b", text: "end" },
	]);
	assert.deepEqual(catalogProse("\\\n# comment=Great job!\nc=d"), [{ field: "c", text: "d" }]);
	assert.deepEqual(catalogProse(String.raw`a\u0062=c\:d \\`), [{ field: "ab", text: "c:d \\" }]);
	assert.deepEqual(catalogProse("  key  :  text\nalone\na\\=b=c"), [
		{ field: "key", text: "text" },
		{ field: "alone", text: "" },
		{ field: "a=b", text: "c" },
	]);
	assert.deepEqual(catalogProse("\u00A0support.x=y"), [{ field: "\u00A0support.x", text: "y" }]);
	const vale = await prepareVale();
	try {
		const alerts = catalogAlerts(vale, ".vale/fixtures/messages-voice.properties");
		const checks = (key: string) =>
			alerts.filter(({ field }) => field === key).map(({ alert }) => alert.Check);
		// The cases that #4894 names: "Great job!" fails, and a support reply may say "please".
		assert.deepEqual(checks("email.done.subject"), ["STE.Exclamation"]);
		assert.deepEqual(checks("support.distress"), []);
		// Each refused word or mark fails, however the catalog writes it.
		assert.deepEqual(checks("email.retry"), ["STE.ProductVoice", "STE.Please"]);
		assert.deepEqual(checks("email.spaced"), ["STE.Exclamation"]);
		assert.deepEqual(checks("email.quoted"), ["STE.Exclamation"]);
		assert.deepEqual(checks("email.placeholder"), ["STE.Exclamation"]);
		assert.deepEqual(checks("email.escaped"), ["STE.Please"]);
		assert.deepEqual(checks("email.code"), ["STE.ProductVoice"]);
		assert.deepEqual(checks("email.joined"), ["STE.Exclamation"]);
		assert.deepEqual(checks("email.adjacent"), ["STE.Exclamation", "STE.ProductVoice"]);
		assert.deepEqual(checks("email.tab"), ["STE.Please", "STE.Exclamation"]);
		assert.deepEqual(checks("email.body"), ["STE.ProductVoice", "STE.Exclamation"]);
		// The exemption is the decoded key's `support.` prefix, and only for "please".
		assert.deepEqual(checks("support.mixed"), ["STE.ProductVoice", "STE.Exclamation"]);
		// A continued line is one message, as Java reads it.
		assert.deepEqual(checks("email.continued"), ["STE.Exclamation"]);
		assert.deepEqual(checks("support.continued"), []);
		assert.deepEqual(checks("support.escaped-key"), []);
		assert.deepEqual(checks("\u00A0support.nbsp"), ["STE.Please"]);
		// A mark before a digit is a merge request, and a key is not prose.
		assert.deepEqual(checks("email.reference"), []);
		assert.deepEqual(checks("email=please"), []);
		assert.equal(alerts.length, 19);
	} finally {
		await vale.dispose();
	}
});

await test('the gate refuses a catalog with "Great job!" and accepts a support reply with "please"', async () => {
	const catalog = "server/application/src/main/resources/messages.properties";
	// The gate's default run reads the enforced paths, so the catalog must be one of them.
	assert.ok(parsePaths(await readFile(".vale/enforced-paths.json", "utf8")).includes(catalog));
	const run = async (message: string) => {
		const directory = await mkdtemp(path.join(tmpdir(), "prose-catalog-"));
		try {
			await mkdir(path.join(directory, path.dirname(catalog)), { recursive: true });
			await writeFile(path.join(directory, catalog), `${message}\n`);
			const { status, stdout } = spawnSync(
				process.execPath,
				[path.resolve("scripts/check-prose.ts"), catalog],
				{ cwd: directory, encoding: "utf8" },
			);
			return { status, stdout };
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	};
	const refused = await run("email.done.subject=Great job!");
	assert.equal(refused.status, 1);
	assert.match(refused.stdout, /email\.done\.subject:1: error STE\.Exclamation/u);
	const allowed = await run("support.distress=If you are in danger now, please call for help.");
	assert.equal(allowed.status, 0);
	assert.match(allowed.stdout, /0 errors/u);
});

await test("the UI report names the check behind each rule message", () => {
	assert.equal(
		uiRuleName("Split this sentence. Write no more than 25 words per sentence."),
		"STE.SentenceLength",
	);
	assert.equal(uiRuleName("Write two sentences instead of a semicolon."), "STE.Semicolons");
	assert.equal(uiRuleName('Remove "Please". The product voice does not plead.'), "STE.Please");
	assert.equal(uiRuleName('Remove "!". The product voice does not exclaim.'), "STE.Exclamation");
	assert.equal(uiRuleName('Remove "Oops". The product voice does not joke.'), "STE.ProductVoice");
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

void test("retired terms fail on shipped docs, UI text, and server messages", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "product-copy-test-"));
	const vale = await prepareVale("product-copy");
	try {
		const docs = "docs/user/getting-started.mdx";
		const ui = "webapp/src/components/FeedbackActions.tsx";
		const server = "server/application/src/main/java/example/FeedbackController.java";
		const catalog = "server/application/src/main/resources/messages.properties";
		const sources = {
			[docs]: [
				"---",
				"title: Feedback",
				"description: View as user",
				"---",
				"",
				"## Per-person results for admins",
				"",
				"Send a nudge.",
				"",
				"Open your AI mentor.",
				"",
				"Read about your mentorship.",
			].join("\n"),
			[ui]: [
				"// View as user",
				"export const actions = () => <>",
				"  <button>View as user</button>",
				'  <button aria-label={"Per-person " + "results for admins"} />',
				"  <button>Send nudges</button>",
				'  <button title="Open your AI mentor" />',
				"</>;",
			].join("\n"),
			[server]: [
				"class FeedbackController {",
				"    // View as user",
				"    void nudge() {}",
				'    String title = "View " + "as user";',
				'    String detail = "Per-person results for admins are off."; ',
				'    String reason = "This nudge expired."; ',
				'    String help = "Open your AI mentor."; ',
				"}",
			].join("\n"),
			[catalog]: "nudge.key=Get feedback.\ncontact.notice=Send a \\u006eudge.\n",
			"docs/decisions/0001-example.md": "View as user",
			"webapp/src/components/FeedbackActions.test.tsx": 'const label = "View as user";',
			"extension/src/api/types.gen.ts": 'const label = "View as user";',
		};
		for (const [file, source] of Object.entries(sources)) {
			await mkdir(path.dirname(path.join(root, file)), { recursive: true });
			await writeFile(path.join(root, file), source);
		}
		const findings = productCopyFindings(vale, root, productCopyPaths(root));
		const expected = {
			[docs]: [3, 6, 8, 10],
			[ui]: [3, 4, 5, 6],
			[server]: [4, 5, 6, 7],
			[catalog]: ["contact.notice"],
		};
		assert.deepEqual(
			findings.map(({ file, line }) => `${file}:${line}`).toSorted(),
			Object.entries(expected)
				.flatMap(([file, lines]) => lines.map((line) => `${file}:${line}`))
				.toSorted(),
		);

		const [first, second] = findings.filter(({ file }) => file === ui);
		assert.ok(first);
		assert.ok(second);
		const firstLabel = `${first.file}: ${first.source}`;
		const secondLabel = `${second.file}: ${second.source}`;
		assert.deepEqual(matchAllowedText([first, second, first], [firstLabel]), {
			unexpected: [second, first],
			unused: [],
		});
		assert.deepEqual(matchAllowedText([first], [firstLabel, secondLabel]), {
			unexpected: [],
			unused: [[secondLabel, 1]],
		});
	} finally {
		await vale.dispose();
		await rm(root, { recursive: true, force: true });
	}
});
