import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { migrationOwnedPracticeScripts } from "./lib/migration-practice-scripts.ts";

const resource = "practices/scripts/retained-authored-practice.ts";
const source = "export default function legacy() {}";
const target = "export default { meta: {}, run() {} };";
const hash = (text: string): string => createHash("sha256").update(text).digest("hex");
const row = (sourceHash = hash(source), targetHash = hash(target)): string =>
	`INSERT INTO _cutover_program VALUES('${sourceHash}',convert_from(decode('${Buffer.from(source).toString("base64")}','base64'),'UTF8'),'${targetHash}',convert_from(decode('${Buffer.from(target).toString("base64")}','base64'),'UTF8'));`;
const change = (sql: string): string =>
	`<databaseChangeLog><changeSet id="programs" author="test"><sql splitStatements="false"><![CDATA[${sql}]]></sql></changeSet></databaseChangeLog>`;

async function fixture(run: (root: string) => Promise<void>): Promise<void> {
	const root = await mkdtemp(path.join(tmpdir(), "migration-practice-owner-"));
	try {
		await mkdir(path.join(root, "db/changelog"), { recursive: true });
		await mkdir(path.dirname(path.join(root, resource)), { recursive: true });
		await writeFile(path.join(root, resource), target);
		await writeFile(
			path.join(root, "db/master.xml"),
			'<databaseChangeLog><include file="changelog/programs.xml" relativeToChangelogFile="true"/></databaseChangeLog>',
		);
		await run(root);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
}

void test("a wired migration's verified literal target owns its exact resource, not an orphan", async () => {
	await fixture(async (root) => {
		await writeFile(path.join(root, "db/changelog/programs.xml"), change(row()));
		const orphan = "practices/scripts/orphan.ts";
		await writeFile(path.join(root, orphan), `${target}\n// unowned`);
		assert.deepEqual(
			await migrationOwnedPracticeScripts(root, new Set([resource, orphan])),
			new Set([resource]),
		);
	});
});

void test("unwired SQL and a fixture declaration cannot own a practice script", async () => {
	await fixture(async (root) => {
		await writeFile(
			path.join(root, "db/master.xml"),
			'<databaseChangeLog><!-- <include file="changelog/programs.xml" relativeToChangelogFile="true"/> --></databaseChangeLog>',
		);
		await writeFile(path.join(root, "db/changelog/programs.xml"), change(row()));
		await writeFile(
			path.join(root, "mapped-program-targets.json"),
			JSON.stringify([{ sourceSha256: hash(source), targetResource: resource }]),
		);
		assert.deepEqual(await migrationOwnedPracticeScripts(root, new Set([resource])), new Set());
	});
});

void test("XML comments, rollback SQL, SQL comments and quoted text do not own a resource", async () => {
	await fixture(async (root) => {
		const sql = `/* outer /* nested */\n${row()}\n*/\n-- ${row()}\n'example\n${row()}\nend'\nSELECT $example$\n${row()}\n$example$;\nSELECT $$\n${row()}\n$$;`;
		await writeFile(
			path.join(root, "db/changelog/programs.xml"),
			`<databaseChangeLog><!-- <changeSet><sql>${row()}</sql></changeSet> --><changeSet id="examples" author="test"><sql><![CDATA[${sql}]]></sql><rollback><sql><![CDATA[${row()}]]></sql></rollback></changeSet></databaseChangeLog>`,
		);
		assert.deepEqual(await migrationOwnedPracticeScripts(root, new Set([resource])), new Set());
	});
});

void test("literal source and target digests must both match their decoded bytes", async () => {
	for (const sql of [row("0".repeat(64)), row(hash(source), "0".repeat(64))]) {
		await fixture(async (root) => {
			await writeFile(path.join(root, "db/changelog/programs.xml"), change(sql));
			await assert.rejects(
				migrationOwnedPracticeScripts(root, new Set([resource])),
				/source or target SHA-256/u,
			);
		});
	}
});

void test("a one-byte resource edit loses its migration ownership", async () => {
	await fixture(async (root) => {
		await writeFile(path.join(root, "db/changelog/programs.xml"), change(row()));
		await writeFile(path.join(root, resource), `${target}\n`);
		assert.deepEqual(await migrationOwnedPracticeScripts(root, new Set([resource])), new Set());
	});
});

void test("flat XML includeAll owns its verified script and allows an empty future directory", async () => {
	await fixture(async (root) => {
		await writeFile(
			path.join(root, "db/master.xml"),
			'<databaseChangeLog><includeAll path="changelog/next" relativeToChangelogFile="true" maxDepth="1" endsWithFilter=".xml" errorIfMissingOrEmpty="false"/></databaseChangeLog>',
		);
		assert.deepEqual(await migrationOwnedPracticeScripts(root, new Set([resource])), new Set());
		await mkdir(path.join(root, "db/changelog/next"));
		await writeFile(
			path.join(root, "db/changelog/next/20261031003000-programs.xml"),
			change(row()),
		);
		assert.deepEqual(
			await migrationOwnedPracticeScripts(root, new Set([resource])),
			new Set([resource]),
		);
	});
});
