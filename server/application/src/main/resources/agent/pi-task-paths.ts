import path from "node:path";

export const SUPPORTED_SCHEMA_VERSION = 7;

export function taskPaths(value: unknown) {
	if (typeof value !== "object" || value === null || Array.isArray(value)) {
		throw new Error("task.json must be an object");
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
			throw new Error(`task.json: ${key} must be a normalized workspace-relative path`);
		}
		return candidate;
	}
	return {
		contextRoot: workspacePath("contextRoot"),
		repositoryRoot: workspacePath("repositoryRoot"),
		manifest: workspacePath("manifest"),
		practiceIndex: workspacePath("practiceIndex"),
		preparedFeedback: workspacePath("preparedFeedback"),
		practiceScripts: workspacePath("practiceScripts"),
	};
}

export function resolveTaskPaths(root: string, value: unknown) {
	const paths = taskPaths(value);
	return {
		contextRoot: path.resolve(root, paths.contextRoot),
		repositoryRoot: path.resolve(root, paths.repositoryRoot),
		manifest: path.resolve(root, paths.manifest),
		practiceIndex: path.resolve(root, paths.practiceIndex),
		preparedFeedback: path.resolve(root, paths.preparedFeedback),
		practiceScripts: path.resolve(root, paths.practiceScripts),
	};
}
