import { isRecord } from "./pi-observation-normalize.ts";

/** Primary readiness is not the file inventory: a folder also holds permitted work from other areas. */
export function folderCitationIndex(value: unknown): {
	availableSourceKinds: Set<string>;
	artifactSources: Map<string, string>;
} {
	if (!isRecord(value) || !Array.isArray(value.sources) || !Array.isArray(value.artifacts)) {
		throw new Error("Folder index: expected sources and artifacts arrays");
	}
	const availableSourceKinds = new Set<string>();
	const artifactSources = new Map<string, string>();
	for (const source of value.sources) {
		if (!isRecord(source) || typeof source.kind !== "string" || !isRecord(source.state)) {
			throw new Error("Folder index: invalid readiness source");
		}
		if (source.state.availability === "AVAILABLE") {
			availableSourceKinds.add(source.kind);
		}
	}
	for (const entry of value.artifacts) {
		if (
			!isRecord(entry) ||
			typeof entry.kind !== "string" ||
			!isRecord(entry.artifact) ||
			typeof entry.artifact.path !== "string" ||
			entry.artifact.path.startsWith("/") ||
			entry.artifact.path.split("/").some((part) => part === ".." || part === "." || part === "") ||
			entry.artifact.path.includes("\\")
		) {
			throw new Error("Folder index: invalid artifact identity");
		}
		if (artifactSources.has(entry.artifact.path)) {
			throw new Error("Folder index: duplicate artifact path");
		}
		// Composed views have no per-record identity; cite their canonical source files instead.
		if (entry.artifact.path.startsWith("inputs/history/")) {
			continue;
		}
		artifactSources.set(entry.artifact.path, entry.kind);
		availableSourceKinds.add(entry.kind);
	}
	return { availableSourceKinds, artifactSources };
}
