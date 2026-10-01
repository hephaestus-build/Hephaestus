/** Validates the shipped source-use approvals and practice policies. Folder proofs are tested by the JVM. */
import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import addFormats from "ajv-formats";
import Ajv2020 from "ajv/dist/2020.js";
import { asArray, asRecord, asString, at, isRecord, parseJson, readJsonFile } from "./lib/json.ts";

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
// Published schema identifiers are immutable even when their classpath directory changes.
const schemaId = (version: string, file: string): string =>
	`https://hephaestus.aet.cit.tum.de/contracts/artifact-source/${version}/${file}`;

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
		if (file.endsWith(".schema.json")) {
			const label = `${version}/${file}`;
			const schema = await readSchema(path.join(versionDir, file), label);
			validateSchemaDocumentation(schema, label);
			ajv.addSchema(schema);
		}
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

const validatePolicySchema = (version: string): void => {
	const id = schemaId(version, "practice-automated-review-policy.schema.json");
	const reviewed = {
		sourceContractVersion: version,
		automatedReview: {
			mode: "LANGUAGE_MODEL",
			evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
		},
		whenEvidenceIsInsufficient: "SKIP_AUTOMATED_REVIEW",
		knownLimitations: [
			{
				code: "RUNTIME_BEHAVIOR_NOT_OBSERVED",
				description: "Repository evidence does not establish runtime behaviour.",
			},
		],
	};
	const reason = {
		code: "NEEDS_A_PERSON",
		description: "Judging this needs context no source carries.",
	};
	validate(id, reviewed, `${version} reviewed-practice policy`);
	validate(
		id,
		{
			...reviewed,
			automatedReview: {
				...reviewed.automatedReview,
				evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
			},
			insufficiencyReason: reason,
		},
		`${version} declared-insufficient policy`,
	);
	validate(
		id,
		{
			...reviewed,
			automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
			knownLimitations: [],
		},
		`${version} human-only policy`,
	);
	const invalid: readonly (readonly [string, unknown])[] = [
		[
			"a review mode with no sufficiency verdict",
			{ ...reviewed, automatedReview: { mode: "LANGUAGE_MODEL", evidenceSufficiency: "NONE" } },
		],
		[
			"a practice it does not review claiming sufficient evidence",
			{
				...reviewed,
				automatedReview: { mode: "NONE", evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET" },
				knownLimitations: [],
			},
		],
		[
			"limitations on a practice it does not review",
			{ ...reviewed, automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" } },
		],
		// The reason a person is needed is its own field. Folded back into the limitation list it stops
		// being answerable, which is the question an operator actually asks of this record.
		[
			"insufficient evidence with no reason a person is needed",
			{
				...reviewed,
				automatedReview: {
					...reviewed.automatedReview,
					evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
				},
			},
		],
		[
			"a reason a person is needed on a practice it does review",
			{ ...reviewed, insufficiencyReason: reason },
		],
		["a retired evidence profile", { ...reviewed, evidenceProfile: "pull-request-review" }],
		// The sources a review reads live on the bindings, because they depend on what occasioned it.
		[
			"evidence needs on the policy",
			{ ...reviewed, needs: [{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" }] },
		],
		["a limitation with no code", { ...reviewed, knownLimitations: [{ description: "…" }] }],
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
	validate(
		schemaId(version, "artifact-source-catalog.schema.json"),
		parseJson(bytes.toString("utf8")),
		`${version}/catalog.json`,
	);
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
	validatePolicySchema(version);
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
	readonly precomputeScript: string | undefined;
}

const bundledPractices = (value: unknown, label: string): BundledPractice[] =>
	asArray(asRecord(value, label).groups, `${label} groups`).flatMap((group, groupIndex) => {
		const groupLabel = `${label} groups[${groupIndex}]`;
		return asArray(asRecord(group, groupLabel).practices, `${groupLabel} practices`).map(
			(practice, index) => {
				const entry = `${groupLabel} practices[${index}]`;
				const record = asRecord(practice, entry);
				const script = record.precomputeScript;
				return {
					slug: asString(record.slug, `${entry}.slug`),
					precomputeScript:
						script === undefined || script === null
							? undefined
							: asString(script, `${entry}.precomputeScript`),
				};
			},
		);
	});

const practices = bundledPractices(parsedPracticeCatalog, PRACTICE_CATALOG);

const precomputeResourcePrefix = "practices/precompute/";
const precomputeFiles = await readdir(
	path.join(root, "server/application/src/main/resources/practices/precompute"),
);
const precomputeScripts = new Set(
	precomputeFiles
		.filter((file) => file.endsWith(".ts"))
		.map((file) => precomputeResourcePrefix + file),
);
const referencedPrecomputeScripts = new Set<string>();
for (const practice of practices) {
	if (practice.precomputeScript === undefined) {
		continue;
	}
	// The loader checks the script exists. Nothing checks it belongs to the practice that names it,
	// and a script named after a slug is the only thing that keeps the pair findable from either side.
	const expected = `${precomputeResourcePrefix}${practice.slug}.ts`;
	if (practice.precomputeScript !== expected) {
		throw new Error(
			`default-catalog.json practice '${practice.slug}' must name its precompute script '${expected}'`,
		);
	}
	if (!precomputeScripts.has(expected)) {
		throw new Error(
			`default-catalog.json practice '${practice.slug}' names a missing precompute script`,
		);
	}
	referencedPrecomputeScripts.add(expected);
}
for (const script of precomputeScripts) {
	if (!referencedPrecomputeScripts.has(script)) {
		throw new Error(`precompute script '${script}' is not named by any bundled practice`);
	}
}

console.log(
	`Source-use contracts: ${contractVersions.length} version(s) validated (${contractVersions.join(", ")}); ${practices.length} bundled practices satisfy their schema.`,
);
