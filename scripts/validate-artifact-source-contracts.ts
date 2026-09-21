/**
 * Checks the *published* artifact-source contract: the JSON Schemas, and the two catalogs they describe.
 *
 * What this deliberately does not check: the semantics. Nothing in the JVM reads these schemas — the
 * server parses the same JSON with hand-written Jackson code and enforces every rule itself, against the
 * shipped resources, under unit test: `ArtifactSourceContract` refuses a source whose authority, identity,
 * completeness and required quality disagree; `ClasspathArtifactSourceCatalogRegistry` refuses use
 * decisions that do not cover every purpose; `SourceCapture` refuses an absent source carrying artifacts;
 * `PracticeDefinitionValidator` refuses a practice reading a source that cannot apply to what it reviews,
 * and `BundledPracticeCatalogLoaderTest` runs the shipped catalog through it. Restating any of that here
 * would buy a second opinion that can only drift from the one that decides.
 *
 * So this owns what nothing in the JVM does:
 *
 *   - the schemas compile, are documented, and actually reject what they claim to reject;
 *   - they pin the exact catalog bytes they were written against;
 *   - their per-artifact-kind evidence allow-lists are checked against the catalog rather than trusted
 *     as a copy of it — they are the one place a new source has to be repeated, so they are the one
 *     place it will be forgotten;
 *   - the bundled practice catalog satisfies its own schema, which the loader cannot tell you: Jackson
 *     reads the fields it knows and says nothing about a retired one left behind;
 *   - every precompute script on disk belongs to a practice, and every practice's script is on disk.
 */
import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

import addFormats from "ajv-formats";
import Ajv2020 from "ajv/dist/2020.js";

import {
	asArray,
	asRecord,
	asString,
	asStringArray,
	at,
	isRecord,
	parseJson,
	readJsonFile,
} from "./lib/json.ts";

const root = path.resolve(import.meta.dirname, "..");
const contractsRoot = path.join(
	root,
	"server/application/src/main/resources/contracts/artifact-source",
);
const contractEntries = await readdir(contractsRoot, { withFileTypes: true });
const contractVersions = contractEntries
	.filter((entry) => entry.isDirectory())
	.map((entry) => entry.name)
	.toSorted();
if (contractVersions.length === 0) {
	throw new Error("No artifact-source contract versions found");
}
const schemaId = (version: string, file: string): string =>
	`https://hephaestus.aet.cit.tum.de/contracts/artifact-source/${version}/${file}`;

/** The one shape this file reads out of a catalog; Ajv checks the rest against the published schema. */
interface CatalogSource {
	readonly kind: string;
	readonly artifactKinds: readonly string[];
}

const toCatalogSources = (value: unknown, label: string): readonly CatalogSource[] =>
	asArray(asRecord(value, label).sources, `${label} sources`).map((source, index) => {
		const entry = `${label} sources[${index}]`;
		const record = asRecord(source, entry);
		return {
			kind: asString(record.kind, `${entry}.kind`),
			artifactKinds: asStringArray(record.artifactKinds, `${entry}.artifactKinds`),
		};
	});

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

const rejectDuplicates = (values: readonly string[], label: string): void => {
	const seen = new Set<string>();
	for (const value of values) {
		if (seen.has(value)) {
			throw new Error(`${label} duplicates '${value}'`);
		}
		seen.add(value);
	}
};

const expectRejection = (id: string, value: unknown, label: string): void => {
	if (ajv.validate(id, value)) {
		throw new Error(label);
	}
};

const sameSet = (actual: readonly string[], expected: readonly string[]): boolean => {
	const left = [...new Set(actual)].toSorted();
	const right = [...new Set(expected)].toSorted();
	return left.length === right.length && left.every((value, index) => value === right[index]);
};

/**
 * The schemas that travel with a run repeat the catalog: every source kind, every artifact kind, and
 * which sources may appear for which kind. That repetition is unavoidable — a JSON Schema cannot read
 * another file — so it is checked rather than trusted. A source added to the catalog and forgotten here
 * is a source the runtime can capture and the manifest schema rejects, which surfaces as a failed run
 * long after the commit that caused it.
 */
const validateSchemasTrackTheCatalog = async (
	version: string,
	sources: readonly CatalogSource[],
	digest: string,
): Promise<void> => {
	const sourceKinds = sources.map((source) => source.kind);
	const artifactKinds = [...new Set(sources.flatMap((source) => source.artifactKinds))];
	const sourcesFor = (artifactKind: string): string[] =>
		sources
			.filter((source) => source.artifactKinds.includes(artifactKind))
			.map((source) => source.kind);

	const catalogSchemaLabel = `${version}/artifact-source-catalog.schema.json`;
	const catalogSchema = await readSchema(
		path.join(contractsRoot, version, "artifact-source-catalog.schema.json"),
		catalogSchemaLabel,
	);
	// A kind the schema lets a source claim but no source supplies is a kind a practice can be authored
	// against and no review can ever read evidence for.
	const declarablePath = ["$defs", "source", "properties", "artifactKinds", "items", "enum"];
	const declarable = asStringArray(
		at(catalogSchema, declarablePath, catalogSchemaLabel),
		`${catalogSchemaLabel} ${declarablePath.join(".")}`,
	);
	if (!sameSet(declarable, artifactKinds)) {
		throw new Error(
			`${version} catalog schema allows artifact kinds no source supplies: ${declarable.filter((kind) => !artifactKinds.includes(kind)).join(", ") || "(none)"}`,
		);
	}

	// Both records name the artifact kind they describe, out of the same list the catalog supplies.
	for (const file of [
		"artifact-source-manifest.schema.json",
		"automated-review-readiness-report.schema.json",
	]) {
		const label = `${version}/${file}`;
		const schema = await readSchema(path.join(contractsRoot, version, file), label);
		const listed = asStringArray(
			at(schema, ["properties", "artifactKind", "enum"], label),
			`${label} properties.artifactKind.enum`,
		);
		if (!sameSet(listed, artifactKinds)) {
			throw new Error(`${label} does not list exactly the catalog's artifact kinds`);
		}
	}

	// Checked through Ajv rather than by walking the schema, so a restructured conditional that still
	// enforces the same allow-list keeps passing and one that quietly stops enforcing it does not.
	const manifestId = schemaId(version, "artifact-source-manifest.schema.json");
	const readinessId = schemaId(version, "automated-review-readiness-report.schema.json");
	const capturedAt = "2026-08-03T00:00:00Z";
	const absent = { availability: "NOT_COLLECTED", reasonCode: "GOVERNANCE_NOT_EFFECTIVE" };
	const captureOf = (kind: string) => ({ kind, state: absent, artifacts: [] });
	const manifestOf = (artifactKind: string, kinds: readonly string[]) => ({
		contractVersion: version,
		catalogDigest: digest,
		artifactKind,
		capturedAt,
		sources: kinds.map(captureOf),
	});
	const sourceCheckOf = (kind: string) => ({
		sourceKind: kind,
		sourceContractVersion: version,
		checkedAt: capturedAt,
		temporalAnchor: capturedAt,
		meetsRequirements: true,
		reasonCodes: [],
	});
	const decisionOf = (kind: string) => ({
		practiceSlug: "example",
		decidedAt: capturedAt,
		ready: true,
		reasonCodes: [],
		sourceChecks: [sourceCheckOf(kind)],
	});
	const readinessOf = (artifactKind: string, kind: string) => ({
		contractVersion: version,
		catalogDigest: digest,
		artifactKind,
		manifestCapturedAt: capturedAt,
		decidedAt: capturedAt,
		decisions: [decisionOf(kind)],
	});

	for (const artifactKind of artifactKinds) {
		const [required, ...rest] = sourcesFor(artifactKind);
		if (required === undefined) {
			throw new Error(`${version} catalog supplies no source for ${artifactKind}`);
		}
		const applicable = [required, ...rest];
		validate(
			manifestId,
			manifestOf(artifactKind, applicable),
			`${version} ${artifactKind} manifest`,
		);
		// A manifest enumerates every source that could have applied, including the ones it did not
		// collect. Dropping one is how "we chose not to look" becomes indistinguishable from silence.
		expectRejection(
			manifestId,
			manifestOf(artifactKind, rest),
			`${version}/artifact-source-manifest.schema.json accepted a ${artifactKind} manifest missing '${required}'`,
		);
		for (const kind of sourceKinds) {
			const applies = applicable.includes(kind);
			if (!applies) {
				expectRejection(
					manifestId,
					manifestOf(artifactKind, [kind, ...rest]),
					`${version}/artifact-source-manifest.schema.json accepted '${kind}' for ${artifactKind}`,
				);
			}
			const label = `${version}/automated-review-readiness-report.schema.json`;
			if (applies) {
				validate(readinessId, readinessOf(artifactKind, kind), `${label} ${artifactKind}/${kind}`);
			} else {
				expectRejection(
					readinessId,
					readinessOf(artifactKind, kind),
					`${label} accepted a source check on '${kind}' for ${artifactKind}`,
				);
			}
		}
	}

	// The rejection paths that are not about the allow-list, exercised against the same fixtures.
	const pullRequestKind =
		artifactKinds.find((kind) => sourcesFor(kind).length > 1) ?? artifactKinds[0];
	if (pullRequestKind === undefined) {
		throw new Error(`${version} catalog supplies no artifact kind`);
	}
	const applicable = sourcesFor(pullRequestKind);
	const [firstKind] = applicable;
	if (firstKind === undefined) {
		throw new Error(`${version} catalog supplies no source for ${pullRequestKind}`);
	}
	const manifest = manifestOf(pullRequestKind, applicable);
	const available = {
		availability: "AVAILABLE",
		content: "NON_EMPTY",
		completeness: "COMPLETE",
		facts: { capturedAt },
	};
	const artifact = {
		path: "context.json",
		mediaType: "application/json",
		sha256: "b".repeat(64),
		bytes: 1,
	};
	const withFirstSource = (state: object, artifacts: object[]) => ({
		...manifest,
		sources: [{ kind: firstKind, state, artifacts }, ...manifest.sources.slice(1)],
	});
	validate(
		manifestId,
		withFirstSource(available, [artifact]),
		`${version} available-source manifest`,
	);
	const invalidManifests: readonly (readonly [string, unknown])[] = [
		["an empty source list", { ...manifest, sources: [] }],
		[
			"an unknown source kind",
			manifestOf(pullRequestKind, ["scm.unknown.source", ...applicable.slice(1)]),
		],
		[
			"a duplicate source capture",
			{ ...manifest, sources: [...manifest.sources, captureOf(firstKind)] },
		],
		["a wrong catalog digest", { ...manifest, catalogDigest: "a".repeat(64) }],
		["an absent source carrying artifacts", withFirstSource(absent, [artifact])],
		["an escaping artifact path", withFirstSource(available, [{ ...artifact, path: "../secret" }])],
		[
			"a non-canonical artifact path",
			withFirstSource(available, [{ ...artifact, path: "./context.json" }]),
		],
	];
	for (const [label, invalid] of invalidManifests) {
		expectRejection(
			manifestId,
			invalid,
			`${version}/artifact-source-manifest.schema.json accepted ${label}`,
		);
	}

	const readiness = readinessOf(pullRequestKind, firstKind);
	const decision = decisionOf(firstKind);
	const check = sourceCheckOf(firstKind);
	const withDecision = (overrides: Record<string, unknown>) => ({
		...readiness,
		decisions: [{ ...decision, ...overrides }],
	});
	// A practice can be skipped before any source is read at all — nothing to review it with, or the
	// author declaring the evidence insufficient. That says nothing about the sources, so it carries no
	// source checks and must still carry a reason.
	validate(
		readinessId,
		withDecision({ ready: false, reasonCodes: ["NO_AUTOMATED_REVIEW"], sourceChecks: [] }),
		`${version} skipped-practice readiness`,
	);
	const invalidDecisions: readonly (readonly [string, Record<string, unknown>])[] = [
		["a ready decision with no source check", { sourceChecks: [] }],
		["a duplicate source check", { sourceChecks: [check, sourceCheckOf(firstKind)] }],
		[
			"a ready decision over a failed source check",
			{
				sourceChecks: [
					{ ...check, meetsRequirements: false, reasonCodes: ["SOURCE_NOT_AVAILABLE"] },
				],
			},
		],
		["a skipped practice with no reason", { ready: false }],
		[
			"a failed source check with no reason",
			{ ready: false, sourceChecks: [{ ...check, meetsRequirements: false }] },
		],
		[
			"a passing source check carrying a reason",
			{ sourceChecks: [{ ...check, reasonCodes: ["SOURCE_INCOMPLETE"] }] },
		],
	];
	for (const [label, overrides] of invalidDecisions) {
		expectRejection(
			readinessId,
			withDecision(overrides),
			`${version}/automated-review-readiness-report.schema.json accepted ${label}`,
		);
	}
};

/**
 * The policy schema describes a shape that only ever exists in memory and in the database, so nothing
 * on disk exercises it. These fixtures are what keeps it honest; `PracticeAutomatedReviewPolicySchemaTest`
 * is what keeps it in step with the record it describes.
 */
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

const validateContractVersion = async (version: string): Promise<void> => {
	const versionDir = path.join(contractsRoot, version);
	const catalogBytes = await readFile(path.join(versionDir, "catalog.json"));
	const parsedCatalog = parseJson(catalogBytes.toString("utf8"));
	const catalogDigest = createHash("sha256").update(catalogBytes).digest("hex");
	// Both runtime records name the catalog they were interpreted under by digest, so a catalog edit
	// that leaves them behind must fail here rather than produce records nobody can interpret.
	for (const schemaFile of [
		"artifact-source-manifest.schema.json",
		"automated-review-readiness-report.schema.json",
	]) {
		const label = `${version}/${schemaFile}`;
		const schema = await readSchema(path.join(versionDir, schemaFile), label);
		const pinned = asString(
			at(schema, ["properties", "catalogDigest", "const"], label),
			`${label} properties.catalogDigest.const`,
		);
		if (pinned !== catalogDigest) {
			throw new Error(`${label} pins ${pinned} but the catalog hashes to ${catalogDigest}`);
		}
	}

	validate(
		schemaId(version, "artifact-source-catalog.schema.json"),
		parsedCatalog,
		`${version}/catalog.json`,
	);
	const sources = toCatalogSources(parsedCatalog, `${version}/catalog.json`);
	rejectDuplicates(
		sources.map((source) => source.kind),
		`${version} source kind`,
	);
	validate(
		schemaId(version, "source-use-decisions.schema.json"),
		await readJsonFile(path.join(versionDir, "source-use-decisions.json")),
		`${version}/source-use-decisions.json`,
	);

	await validateSchemasTrackTheCatalog(version, sources, catalogDigest);
	validatePolicySchema(version);
};

for (const version of contractVersions) {
	await validateContractVersion(version);
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
	`Artifact-source contracts: ${contractVersions.length} version(s) validated (${contractVersions.join(", ")}); ${practices.length} bundled practices satisfy their schema.`,
);
