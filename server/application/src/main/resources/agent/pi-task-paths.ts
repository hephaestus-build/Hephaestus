import { resolve } from "node:path";

export const SUPPORTED_SCHEMA_VERSION = 2;

export function taskPaths(value: unknown) {
	if (typeof value !== "object" || value === null || Array.isArray(value)) {
		throw new Error("task.json: paths must be an object");
	}
	const object = value;
	function path(key: string): string {
		const candidate: unknown = Reflect.get(object, key);
		if (
			typeof candidate !== "string" ||
			/^\p{Z}*$/u.test(candidate) ||
			/[\\:\p{Cc}]/u.test(candidate) ||
			candidate.split("/").some((part) => !part || part === "." || part === "..")
		) {
			throw new Error(`task.json: paths.${key} must be a normalized workspace-relative path`);
		}
		return candidate;
	}
	return {
		contextRoot: path("contextRoot"),
		repositoryRoot: path("repositoryRoot"),
		manifest: path("manifest"),
		practiceIndex: path("practiceIndex"),
		compositionRequest: path("compositionRequest"),
		preparedFeedback: path("preparedFeedback"),
		precomputeScripts: path("precomputeScripts"),
	};
}

export function resolveTaskPaths(root: string, value: unknown) {
	const paths = taskPaths(value);
	return {
		contextRoot: resolve(root, paths.contextRoot),
		repositoryRoot: resolve(root, paths.repositoryRoot),
		manifest: resolve(root, paths.manifest),
		practiceIndex: resolve(root, paths.practiceIndex),
		compositionRequest: resolve(root, paths.compositionRequest),
		preparedFeedback: resolve(root, paths.preparedFeedback),
		precomputeScripts: resolve(root, paths.precomputeScripts),
	};
}
