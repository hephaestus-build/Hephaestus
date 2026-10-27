import assert from "node:assert/strict";
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import {
	buildModuleDiagrams,
	type ColumnInfo,
	MERMAID_MAX_TEXT_SIZE,
	readModuleOwnership,
	type RelationshipInfo,
	tableNames,
} from "./generate-mermaid-erd.ts";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const erdDirectory = path.join(repositoryRoot, "docs/contributor/erd");

const column = (name: string): ColumnInfo => ({
	name,
	type: "BIGINT",
	constraints: [],
	comment: "",
	isPrimaryKey: name === "id",
	isForeignKey: name !== "id",
});

const foreignKey = (childTable: string, parentTable: string): RelationshipInfo => ({
	childTable,
	childColumn: `${parentTable}_id`,
	parentTable,
	parentColumn: "id",
	constraintName: `fk_${childTable}_${parentTable}`,
	label: "has",
	cardinality: "||--o{",
});

await describe("tableNames", async () => {
	await test("reads the annotation's own name wherever it stands, not a nested column's", () => {
		const source = [
			'@Table(schema = "public", name = "issue", indexes = {@Index(name = "idx_issue")})',
			'@JoinTable(joinColumns = @JoinColumn(name = "issue_id"), name = "issue_label")',
			String.raw`@CollectionTable(name = "\"order\"")`,
			"/** See {@Table} for the unique constraints. */",
		].join("\n");

		assert.deepEqual(tableNames(source), ["issue", "issue_label", "order"]);
	});
});

await describe("readModuleOwnership", async () => {
	await test("gives each mapped table the module of its source package", async (t) => {
		const root = await mkdtemp(path.join(tmpdir(), "erd-modules-"));
		t.after(async () => rm(root, { recursive: true, force: true }));
		await mkdir(path.join(root, "workspace/domain"), { recursive: true });
		await mkdir(path.join(root, "integration"), { recursive: true });
		await writeFile(
			path.join(root, "workspace/package-info.java"),
			'@ApplicationModule(displayName = "Workspace (Tenancy)")\npackage x.workspace;',
		);
		await writeFile(
			path.join(root, "workspace/domain/Workspace.java"),
			'@Entity\n@Table(\n        name = "workspace",\n        uniqueConstraints = {})\nclass Workspace {\n  @JoinTable(name = "workspace_member")\n}',
		);
		await writeFile(
			path.join(root, "integration/User.java"),
			'@Table(name = "\\"user\\"")\nclass User {}',
		);

		const { modules, owners } = await readModuleOwnership(root);

		assert.deepEqual(modules, [
			{ id: "integration", displayName: "Integration" },
			{ id: "workspace", displayName: "Workspace (Tenancy)" },
		]);
		assert.deepEqual(
			owners,
			new Map([
				["user", "integration"],
				["workspace", "workspace"],
				["workspace_member", "workspace"],
			]),
		);
	});

	await test("refuses a table that two modules map", async (t) => {
		const root = await mkdtemp(path.join(tmpdir(), "erd-modules-"));
		t.after(async () => rm(root, { recursive: true, force: true }));
		for (const module of ["core", "agent"]) {
			await mkdir(path.join(root, module));
			await writeFile(path.join(root, module, "Job.java"), '@Table(name = "job")');
		}

		await assert.rejects(readModuleOwnership(root), /job is mapped in both/u);
	});
});

await describe("buildModuleDiagrams", async () => {
	const tables = new Map([
		["workspace", [column("id")]],
		["membership", [column("id"), column("workspace_id"), column("user_id")]],
		["user", [column("id")]],
		["shedlock", [column("name")]],
	]);
	const files = buildModuleDiagrams(
		tables,
		[foreignKey("membership", "workspace"), foreignKey("membership", "user")],
		{
			modules: [
				{ id: "integration", displayName: "Integration" },
				{ id: "workspace", displayName: "Workspace" },
			],
			owners: new Map([
				["user", "integration"],
				["workspace", "workspace"],
				["membership", "workspace"],
			]),
		},
	);

	await test("draws a foreign key to another module once, against a reference to that module", () => {
		const workspace = files.get("workspace.mmd") ?? "";
		assert.match(workspace, /^ {4}User\["User · Integration"\]$/mu);
		assert.match(workspace, /^ {4}class User referenceEntity$/mu);
		assert.match(workspace, /^ {4}User \|\|--o\{ Membership : has$/mu);
		assert.doesNotMatch(files.get("integration.mmd") ?? "", /Membership/u);
	});

	await test("puts a table that no entity maps in its own diagram", () => {
		assert.match(files.get("without-entity.mmd") ?? "", /^ {4}Shedlock \{$/mu);
	});

	await test("refuses a diagram that Mermaid would not draw", () => {
		const wide = new Map([
			["wide", Array.from({ length: 2000 }, (_, index) => column(`column_${index}`))],
		]);
		assert.throws(
			() =>
				buildModuleDiagrams(wide, [], {
					modules: [{ id: "core", displayName: "Core" }],
					owners: new Map([["wide", "core"]]),
				}),
			/Core diagram has \d+ characters/u,
		);
	});

	await test("renders one section per module, in module order", () => {
		const headings = [...(files.get("_modules.mdx") ?? "").matchAll(/^## (?<heading>.+)$/gmu)].map(
			(match) => match.groups?.heading,
		);
		assert.deepEqual(headings, ["Integration", "Workspace", "Tables without an entity"]);
	});
});

// The docs build does not render Mermaid: only this keeps an oversized diagram from shipping as an error.
await test("every committed diagram fits Mermaid's text limit", async () => {
	const entries = await readdir(erdDirectory);
	const diagrams = entries.filter((name) => name.endsWith(".mmd"));
	assert.ok(diagrams.length > 0, `${erdDirectory} holds no diagrams`);
	for (const name of diagrams) {
		const diagram = await readFile(path.join(erdDirectory, name), "utf8");
		const { length } = diagram;
		assert.ok(
			length < MERMAID_MAX_TEXT_SIZE,
			`${name} has ${length} characters; Mermaid renders no diagram above ${MERMAID_MAX_TEXT_SIZE}.`,
		);
	}
});
