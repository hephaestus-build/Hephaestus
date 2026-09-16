import path from "node:path";

export const SUPPORTED_SCHEMA_VERSION = 2;

export function taskPaths(value: unknown) {
	if (typeof value !== "object" || value === null || Array.isArray(value)) {
		throw new Error("task.json: paths must be an object");
	}
	const object = value;
	function workspacePath(key: string): string {
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
		contextRoot: workspacePath("contextRoot"),
		repositoryRoot: workspacePath("repositoryRoot"),
		manifest: workspacePath("manifest"),
		practiceIndex: workspacePath("practiceIndex"),
		compositionRequest: workspacePath("compositionRequest"),
		preparedFeedback: workspacePath("preparedFeedback"),
		precomputeScripts: workspacePath("precomputeScripts"),
	};
}

export function resolveTaskPaths(root: string, value: unknown) {
	const paths = taskPaths(value);
	return {
		contextRoot: path.resolve(root, paths.contextRoot),
		repositoryRoot: path.resolve(root, paths.repositoryRoot),
		manifest: path.resolve(root, paths.manifest),
		practiceIndex: path.resolve(root, paths.practiceIndex),
		compositionRequest: path.resolve(root, paths.compositionRequest),
		preparedFeedback: path.resolve(root, paths.preparedFeedback),
		precomputeScripts: path.resolve(root, paths.precomputeScripts),
	};
}
