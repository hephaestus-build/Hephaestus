import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { appendInclude, parseCommand, promote, promoteDraft, validateDraft } from "./db-utils.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";

const generated = `<?xml version="1.1" encoding="UTF-8" standalone="no"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
    <changeSet author="root (generated)" id="1788329906048-6">
        <dropForeignKeyConstraint baseTableName="consent_decision" constraintName="fk_consent_decision_notice"/>
    </changeSet>
    <changeSet author="root (generated)" id="1788329906048-3">
        <addNotNullConstraint columnDataType="timestamp(6) with timezone" columnName="updated_at" tableName="outline_collection" validate="true"/>
    </changeSet>
</databaseChangeLog>
`;

void test("a draft becomes a new changelog with sequential ids and one author", () => {
	const changelog = promoteDraft(generated, 1_700_000_000_000);
	assert.match(changelog, /^<\?xml version="1\.0" encoding="UTF-8"\?>\n<databaseChangeLog /u);
	assert.match(changelog, /dbchangelog-latest\.xsd/u);
	assert.deepEqual(
		[...changelog.matchAll(/<changeSet id="(?<id>[^"]+)" author="hephaestus">/gu)].map(
			(m) => m.groups?.id,
		),
		["1700000000000-1", "1700000000000-2"],
	);
	assert.doesNotMatch(changelog, /root \(generated\)|version="1\.1"/u);
	assert.match(changelog, /dropForeignKeyConstraint[\s\S]*addNotNullConstraint/u);
	assert.ok(changelog.endsWith("</databaseChangeLog>\n"));
});

void test("a draft appends to the changelog this branch already added, continuing its numbering", () => {
	const existing = promoteDraft(generated, 1_700_000_000_000);
	const appended = promoteDraft(generated, 1_700_000_000_000, existing);
	assert.deepEqual(
		[...appended.matchAll(/<changeSet id="(?<id>[^"]+)"/gu)].map((m) => m.groups?.id),
		["1700000000000-1", "1700000000000-2", "1700000000000-3", "1700000000000-4"],
	);
	assert.equal(appended.split("</databaseChangeLog>").length, 2);
});

void test("an empty draft is rejected rather than promoted to an empty changelog", () => {
	assert.throws(() => promoteDraft("<databaseChangeLog/>", 1_700_000_000_000), /no change sets/u);
});

void test("master.xml gains the include at the end and never twice", () => {
	const master = `<databaseChangeLog>
    <include file="./changelog/1_changelog.xml" relativeToChangelogFile="true"/>
</databaseChangeLog>
`;
	const once = appendInclude(master, "2_changelog.xml");
	assert.equal(
		once,
		`<databaseChangeLog>
    <include file="./changelog/1_changelog.xml" relativeToChangelogFile="true"/>
    <include file="./changelog/2_changelog.xml" relativeToChangelogFile="true"/>
</databaseChangeLog>
`,
	);
	assert.equal(appendInclude(once, "2_changelog.xml"), once);
});

const nativeSet = `<changeSet id="draft-1" author="draft">
        <preConditions onFail="HALT" onError="HALT">
            <sqlCheck expectedResult="1">SELECT count(*) FROM pg_constraint WHERE conname = 'ck_example'</sqlCheck>
        </preConditions>
        <comment>Replace a native constraint the JPA diff cannot see.</comment>
        <sql><![CDATA[
ALTER TABLE example DROP CONSTRAINT ck_example, ADD CONSTRAINT ck_example CHECK (value > 0 AND value < 10);
        ]]></sql>
        <rollback>
            <stop>Restore a verified backup.</stop>
        </rollback>
    </changeSet>`;
const native = `<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
    ${nativeSet}
</databaseChangeLog>
`;

void test("an authored draft keeps its preconditions, SQL and rollback while promotion assigns id and author", () => {
	validateDraft(native);
	const changelog = promoteDraft(native, 1_700_000_000_000);
	const body = nativeSet.slice(nativeSet.indexOf(">") + 1);
	assert.ok(changelog.includes(`<changeSet id="1700000000000-1" author="hephaestus">${body}`));
	assert.doesNotMatch(changelog, /draft-1|author="draft"/u);
});

const wrap = (inner: string): string => `<databaseChangeLog>${inner}</databaseChangeLog>`;

void test("an authored draft that promotion would silently change is refused", () => {
	for (const [draft, reason] of [
		[wrap(nativeSet.replace("</comment>", "")), /comment/u],
		[`<!DOCTYPE databaseChangeLog [<!ENTITY x SYSTEM "file:///etc/hosts">]>${native}`, /DOCTYPE/u],
		[
			native.replace(
				"<databaseChangeLog ",
				'<databaseChangeLog objectQuotingStrategy="QUOTE_ALL_OBJECTS" ',
			),
			/objectQuotingStrategy/u,
		],
		[
			native.replace(
				"<databaseChangeLog ",
				'<databaseChangeLog xmlns:ext="http://www.liquibase.org/xml/ns/dbchangelog-ext" ',
			),
			/xmlns:ext/u,
		],
		["<databaseChangeLog/>", /no change sets/u],
		[wrap(`<include file="other.xml"/>${nativeSet}`), /Only change sets/u],
		[wrap(`<!-- kept nowhere -->${nativeSet}`), /Only change sets/u],
		[
			wrap(nativeSet.replace('author="draft"', 'author="draft" runInTransaction="false"')),
			/runInTransaction/u,
		],
		[wrap('<changeSet id="a" author="b"></changeSet>'), /makes no change/u],
		[wrap(`<changeSet id="a" author="b"/>${nativeSet}`), /closing tag/u],
	] as const) {
		assert.throws(() => validateDraft(draft), reason, draft);
	}
});

void test("--from-draft belongs to draft-changelog and leaves the generated mode unchanged", () => {
	assert.deepEqual(parseCommand(["draft-changelog"]), {
		command: "draft-changelog",
		help: false,
		fromDraft: undefined,
	});
	assert.equal(parseCommand(["draft-changelog", "--from-draft", "d.xml"]).fromDraft, "d.xml");
	assert.throws(
		() => parseCommand(["check-drift", "--from-draft", "d.xml"]),
		/only to draft-changelog/u,
	);
	assert.throws(() => parseCommand(["draft-changelog", "d.xml"]), /Unexpected arguments: d\.xml/u);
	assert.equal(parseCommand(["--help"]).help, true);
});

for (const second of ["untracked", "staged"] as const) {
	void test(`a branch with a committed and a second, ${second} changelog is refused without a third or a master.xml change`, async () => {
		const repository = await mkdtemp(path.join(tmpdir(), "db-promote-"));
		const env = environmentForGitFixture();
		const git = (...args: string[]) =>
			execFileSync("git", args, { cwd: repository, env, stdio: ["ignore", "pipe", "pipe"] });
		const changelogs = path.join(repository, "server/application/src/main/resources/db/changelog");
		const master = path.join(repository, "server/application/src/main/resources/db/master.xml");
		const masterXml = "<databaseChangeLog>\n</databaseChangeLog>\n";
		try {
			git("init", "--quiet", "--initial-branch=main");
			git("config", "user.name", "Test");
			git("config", "user.email", "test@example.invalid");
			await mkdir(changelogs, { recursive: true });
			await writeFile(master, masterXml);
			await writeFile(path.join(changelogs, "1_changelog.xml"), native);
			git("add", ".");
			git("commit", "--quiet", "-m", "released");
			git("checkout", "--quiet", "-b", "feature");
			await writeFile(path.join(changelogs, "2_changelog.xml"), native);
			git("add", ".");
			git("commit", "--quiet", "-m", "branch changelog");
			await writeFile(path.join(changelogs, "3_changelog.xml"), native);
			if (second === "staged") {
				git("add", ".");
			}

			await assert.rejects(promote(native, repository), /several changelogs/u);
			const listing = await readdir(changelogs);
			assert.deepEqual(listing.toSorted(), [
				"1_changelog.xml",
				"2_changelog.xml",
				"3_changelog.xml",
			]);
			assert.equal(await readFile(master, "utf8"), masterXml);
		} finally {
			await rm(repository, { recursive: true, force: true });
		}
	});
}
