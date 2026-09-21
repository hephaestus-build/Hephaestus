/**
 * What a source file is, from its path: the language, and whether it is a test. One table for every
 * script, so a practice about "the Swift files of the change" and one about "the Kotlin files" name
 * the language the same way and never disagree about an extension.
 */

const EXTENSION_LANGUAGE: Record<string, string> = {
	swift: "swift",
	kt: "kotlin",
	kts: "kotlin",
	java: "java",
	ts: "typescript",
	tsx: "typescript",
	mts: "typescript",
	cts: "typescript",
	js: "javascript",
	jsx: "javascript",
	mjs: "javascript",
	cjs: "javascript",
	cs: "csharp",
	go: "go",
	rs: "rust",
	py: "python",
	rb: "ruby",
	php: "php",
	m: "objective-c",
	mm: "objective-c",
	c: "c",
	h: "c",
	cc: "c",
	cpp: "c",
	cxx: "c",
	hpp: "c",
};

/** The language a path's extension names, or null for a file no script reads as source. */
export function languageOf(path: string): string | null {
	const ext = path.split(".").pop()?.toLowerCase() ?? "";
	return EXTENSION_LANGUAGE[ext] ?? null;
}

/** A test file by the conventions of the common toolchains: a test directory or a test suffix. */
export function isTestPath(path: string): boolean {
	return (
		/(?:^|\/)(?:tests?|__tests__|spec|specs|uitests?|androidTest|testing)\//iu.test(path) ||
		/(?:\.test|\.spec|Tests?|_test|_spec)\.[a-z]+$/u.test(path)
	);
}
