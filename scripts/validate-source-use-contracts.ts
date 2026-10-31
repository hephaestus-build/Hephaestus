/** Validates the shipped source-use approvals and practice policies. Folder proofs are tested by the JVM. */
import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import addFormats from "ajv-formats";
import Ajv2020 from "ajv/dist/2020.js";
import { asArray, asRecord, asString, at, isRecord, parseJson, readJsonFile } from "./lib/json.ts";
import { migrationOwnedPracticeScripts } from "./lib/migration-practice-scripts.ts";

const root = path.resolve(import.meta.dirname, "..");
const contractsRoot = path.join(root, "server/application/src/main/resources/contracts/source-use");
const contractEntries = await readdir(contractsRoot, { withFileTypes: true });
const contractVersions = contractEntries
	.filter((entry) => entry.isDirectory())
	.map((entry) => entry.name)
	.toSorted();
if (contractVersions.length === 0) {
	throw new Error("No source-use contract versions found");
}
const schemaIds = new Map<string, string>();
const catalogSchemaIds = new Map<string, string>();
const schemaId = (version: string, file: string): string => {
	const id = schemaIds.get(`${version}/${file}`);
	if (id === undefined) {
		throw new Error(`Missing source-use schema ${version}/${file}`);
	}
	return id;
};

const requireDescription = (value: unknown, label: string): void => {
	const description = isRecord(value) ? value.description : undefined;
	if (typeof description !== "string" || description.trim() === "") {
		throw new Error(`${label} needs a description`);
	}
};

/** A schema's `properties`, or nothing when it declares none. */
const propertyEntries = (value: unknown): [string, unknown][] => {
	const properties = isRecord(value) ? value.properties : undefined;
	return isRecord(properties) ? Object.entries(properties) : [];
};

/**
 * Every published name explains itself.
 *
 * A source contract is read by practice authors and by whoever has to decide, months later, whether a
 * field still means what it says. An undocumented property is the first step of the rot this whole
 * contract exists to prevent.
 */
const validateSchemaDocumentation = (schema: Record<string, unknown>, label: string): void => {
	const { title } = schema;
	if (typeof title !== "string" || title.trim() === "") {
		throw new Error(`${label} needs a title`);
	}
	requireDescription(schema, label);
	for (const [name, property] of propertyEntries(schema)) {
		requireDescription(property, `${label} property '${name}'`);
	}
	const defs = schema.$defs;
	for (const [name, definition] of isRecord(defs) ? Object.entries(defs) : []) {
		requireDescription(definition, `${label} definition '${name}'`);
		for (const [propertyName, property] of propertyEntries(definition)) {
			requireDescription(property, `${label} definition '${name}.${propertyName}'`);
		}
	}
};

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

const readSchema = async (file: string, label: string): Promise<Record<string, unknown>> =>
	asRecord(await readJsonFile(file), label);

for (const version of contractVersions) {
	const versionDir = path.join(contractsRoot, version);
	for (const file of await readdir(versionDir)) {
		if (!file.endsWith(".schema.json")) {
			continue;
		}
		const label = `${version}/${file}`;
		const schema = await readSchema(path.join(versionDir, file), label);
		validateSchemaDocumentation(schema, label);
		schemaIds.set(label, asString(schema.$id, `${label} $id`));
		if (file.endsWith("-source-catalog.schema.json")) {
			if (catalogSchemaIds.has(version)) {
				throw new Error(`${version} declares multiple source catalog schemas`);
			}
			catalogSchemaIds.set(version, asString(schema.$id, `${label} $id`));
		}
		ajv.addSchema(schema);
	}
}

const defaultCatalogSchemaPath = path.join(
	root,
	"server/application/src/main/resources/practices/default-catalog.schema.json",
);
const defaultCatalogSchema = await readSchema(
	defaultCatalogSchemaPath,
	"practices/default-catalog.schema.json",
);
validateSchemaDocumentation(defaultCatalogSchema, "practices/default-catalog.schema.json");
ajv.addSchema(defaultCatalogSchema);

const validate = (id: string, value: unknown, label: string): void => {
	if (!ajv.validate(id, value)) {
		throw new Error(`${label} violates ${id}: ${ajv.errorsText(ajv.errors)}`);
	}
};

const expectRejection = (id: string, value: unknown, label: string): void => {
	if (ajv.validate(id, value)) {
		throw new Error(label);
	}
};

const validateReviewedBySchema = (version: string): void => {
	const id = schemaId(version, "practice-automated-review-policy.schema.json");
	const reason = {
		code: "NEEDS_A_PERSON",
		description: "This practice needs context no source carries.",
	};
	const limit = {
		code: "RUNTIME_NOT_OBSERVED",
		description: "Captured evidence does not establish runtime behavior.",
	};
	const reviewed = { by: "HEPHAESTUS", humanReason: null, limits: [limit] };
	validate(id, reviewed, `${version} Hephaestus-reviewed practice`);
	validate(
		id,
		{ by: "PEOPLE", humanReason: reason, limits: [] },
		`${version} people-reviewed practice`,
	);
	validate(id, { by: "NOBODY", humanReason: null, limits: [] }, `${version} unreviewed practice`);
	const invalid: readonly (readonly [string, unknown])[] = [
		["an unknown reviewer", { ...reviewed, by: "LANGUAGE_MODEL" }],
		["a missing reviewer", { humanReason: null, limits: [] }],
		["a person review without a reason", { ...reviewed, by: "PEOPLE" }],
		["a human reason on an automated review", { ...reviewed, humanReason: reason }],
		[
			"a human reason on an unreviewed practice",
			{ ...reviewed, by: "NOBODY", humanReason: reason },
		],
		["a limit without a code", { ...reviewed, limits: [{ description: "Missing code" }] }],
		[
			"a retired automatedReview field",
			{ ...reviewed, automatedReview: { mode: "LANGUAGE_MODEL" } },
		],
	];
	for (const [label, value] of invalid) {
		expectRejection(
			id,
			value,
			`${version}/practice-automated-review-policy.schema.json accepted ${label}`,
		);
	}
};

for (const version of contractVersions) {
	const directory = path.join(contractsRoot, version);
	const bytes = await readFile(path.join(directory, "catalog.json"));
	const catalogSchemaId = catalogSchemaIds.get(version);
	if (catalogSchemaId === undefined) {
		throw new Error(`${version} has no source catalog schema`);
	}
	validate(catalogSchemaId, parseJson(bytes.toString("utf8")), `${version}/catalog.json`);
	validate(
		schemaId(version, "source-use-decisions.schema.json"),
		await readJsonFile(path.join(directory, "source-use-decisions.json")),
		`${version}/source-use-decisions.json`,
	);
	const schema = await readSchema(
		path.join(directory, "automated-review-readiness-report.schema.json"),
		version,
	);
	const digest = asString(
		at(schema, ["properties", "catalogDigest", "const"], version),
		`${version} catalog digest`,
	);
	if (digest !== createHash("sha256").update(bytes).digest("hex")) {
		throw new Error(`${version} readiness schema has a stale catalog digest`);
	}
	validateReviewedBySchema(version);
}

const PRACTICE_CATALOG = "practices/default-catalog.json";
const practiceCatalogPath = path.join(
	root,
	"server/application/src/main/resources",
	PRACTICE_CATALOG,
);
const parsedPracticeCatalog = await readJsonFile(practiceCatalogPath);
validate(
	asString(defaultCatalogSchema.$id, "default-catalog.schema.json $id"),
	parsedPracticeCatalog,
	PRACTICE_CATALOG,
);

/** A bundled practice, as far as the script/practice pairing below is concerned. */
interface BundledPractice {
	readonly slug: string;
	readonly scriptResource: string | undefined;
}

const bundledPractices = (value: unknown, label: string): BundledPractice[] =>
	asArray(asRecord(value, label).groups, `${label} groups`).flatMap((group, groupIndex) => {
		const groupLabel = `${label} groups[${groupIndex}]`;
		return asArray(asRecord(group, groupLabel).practices, `${groupLabel} practices`).map(
			(practice, index) => {
				const entry = `${groupLabel} practices[${index}]`;
				const record = asRecord(practice, entry);
				const script = record.scriptResource;
				return {
					slug: asString(record.slug, `${entry}.slug`),
					scriptResource:
						script === undefined || script === null
							? undefined
							: asString(script, `${entry}.scriptResource`),
				};
			},
		);
	});

const practices = bundledPractices(parsedPracticeCatalog, PRACTICE_CATALOG);

const practiceScriptResourcePrefix = "practices/scripts/";
const practiceScriptFiles = await readdir(
	path.join(root, "server/application/src/main/resources/practices/scripts"),
);
const practiceScripts = new Set(
	practiceScriptFiles
		.filter((file) => file.endsWith(".ts"))
		.map((file) => practiceScriptResourcePrefix + file),
);
const referencedPracticeScripts = new Set<string>();
for (const practice of practices) {
	if (practice.scriptResource === undefined) {
		continue;
	}
	// The loader checks the script exists. Nothing checks it belongs to the practice that names it,
	// and a script named after a slug is the only thing that keeps the pair findable from either side.
	const expected = `${practiceScriptResourcePrefix}${practice.slug}.ts`;
	if (practice.scriptResource !== expected) {
		throw new Error(
			`default-catalog.json practice '${practice.slug}' must name its practice script '${expected}'`,
		);
	}
	if (!practiceScripts.has(expected)) {
		throw new Error(
			`default-catalog.json practice '${practice.slug}' names a missing practice script`,
		);
	}
	referencedPracticeScripts.add(expected);
}
const unreferencedScripts = new Set(
	[...practiceScripts].filter((script) => !referencedPracticeScripts.has(script)),
);
const migrationScripts =
	unreferencedScripts.size === 0
		? new Set<string>()
		: await migrationOwnedPracticeScripts(
				path.join(root, "server/application/src/main/resources"),
				unreferencedScripts,
			);
for (const script of unreferencedScripts) {
	if (!migrationScripts.has(script)) {
		throw new Error(
			`practice script '${script}' has no bundled practice or verified migration owner`,
		);
	}
}

console.log(
	`Source-use contracts: ${contractVersions.length} version(s) validated (${contractVersions.join(", ")}); ${practices.length} bundled practices satisfy their schema.`,
);
