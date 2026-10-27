import {
	CANDIDATE_LIMITS,
	type CitationCapabilities,
} from "./work/practice-scripts/lib/core/contract.ts";
import { z } from "./work/practice-scripts/lib/core/practice-script.ts";

const relativePath = z
	.string()
	.min(1)
	.refine(
		(path) =>
			!path.startsWith("/") &&
			!path.includes("\\") &&
			path.split("/").every((segment) => segment !== "." && segment !== ".." && segment !== ""),
	);
const citableTextSchema = z.strictObject({
	source: z.string().min(1),
	path: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
	side: z.enum(["OLD", "NEW"]),
	commit: z
		.string()
		.min(1)
		.max(CANDIDATE_LIMITS.versionChars)
		.nullish()
		.transform((value) => value ?? null),
	stagedPath: relativePath,
	sha256: z.string().regex(/^[0-9a-f]{64}$/u),
});
export type CitableText = z.infer<typeof citableTextSchema>;

/** The manifest owns each logical text's immutable staged bytes. */
export function citableTextIndex(value: unknown): CitableText[] {
	return z.looseObject({ citableTexts: z.array(citableTextSchema).default([]) }).parse(value)
		.citableTexts;
}

/** Primary readiness is not the file inventory: a folder also holds permitted work from other areas. */
export function folderCitationIndex(value: unknown): {
	availableSourceKinds: Set<string>;
	/** Sources captured whole: only a search of one of these can show that it lacks something. */
	completeSourceKinds: Set<string>;
	sourceFileSources: Map<string, string>;
	citationCapabilities: Map<string, CitationCapabilities>;
} {
	const manifest = z
		.looseObject({
			sources: z.array(
				z.looseObject({
					kind: z.string(),
					state: z.looseObject({ availability: z.string(), completeness: z.string().optional() }),
					citation: z.strictObject({
						side: z.enum(["REQUIRED", "OPTIONAL", "FORBIDDEN"]),
						commit: z.enum(["REQUIRED", "OPTIONAL", "FORBIDDEN"]),
					}),
				}),
			),
			files: z.array(
				z.looseObject({ kind: z.string(), file: z.looseObject({ path: relativePath }) }),
			),
		})
		.parse(value);
	const availableSourceKinds = new Set<string>();
	const completeSourceKinds = new Set<string>();
	const sourceFileSources = new Map<string, string>();
	const citationCapabilities = new Map<string, CitationCapabilities>();
	for (const source of manifest.sources) {
		citationCapabilities.set(source.kind, source.citation);
		if (source.state.availability === "AVAILABLE") {
			availableSourceKinds.add(source.kind);
			if (source.state.completeness === "COMPLETE") {
				completeSourceKinds.add(source.kind);
			}
		}
	}
	for (const entry of manifest.files) {
		if (sourceFileSources.has(entry.file.path)) {
			throw new Error("Folder index: duplicate sourceFile path");
		}
		// Composed views have no per-record identity; cite their canonical source files instead.
		if (entry.file.path.startsWith("inputs/history/")) {
			continue;
		}
		sourceFileSources.set(entry.file.path, entry.kind);
		availableSourceKinds.add(entry.kind);
	}
	return { availableSourceKinds, completeSourceKinds, sourceFileSources, citationCapabilities };
}
