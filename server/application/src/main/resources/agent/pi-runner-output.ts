// The worker accepts USTAR only. Enforce its path limits before gateway-run.ts packages out/.
// PAX/GNU extensions are rejected by the worker's bounded archive reader.

/** Archive members are `out/<name>`, and USTAR stores a member name in 100 bytes. */
const MAX_MEMBER_BYTES = 100;
const ARCHIVE_ROOT = "out/";
const PORTABLE_NAME = /^[A-Za-z0-9][A-Za-z0-9._/-]*$/u;

export function outputPath(outputDir: string, name: string): string {
	const member = `${ARCHIVE_ROOT}${name}`;
	if (!PORTABLE_NAME.test(name)) {
		throw new Error(
			`Output file name must start with an ASCII letter or digit and hold only letters, digits, '.', '_', '-' and '/': ${name}`,
		);
	}
	if (name.split("/").some((segment) => segment === "" || segment === "." || segment === "..")) {
		throw new Error(
			`Output file name must contain only non-empty, relative path segments: ${name}`,
		);
	}
	// ASCII by the test above, so one character is one byte.
	if (member.length > MAX_MEMBER_BYTES) {
		throw new Error(
			`Output archive member ${member} is ${member.length} bytes; the limit is ${MAX_MEMBER_BYTES}`,
		);
	}
	return `${outputDir}/${name}`;
}
