// Where every file this runner leaves behind is named.
//
// The worker reads `out/` back as a USTAR tar and refuses a member that a producer had to encode as a
// PAX or GNU name-extension record, because resolving those records is what lets a sparse entry expand
// past the size its header declares. gateway-run.ts packages `out/` with `tar --format=ustar`, which
// refuses a name it cannot hold, so such a name fails the whole review with a message about the
// archive. Naming the rule here fails the one file instead, in the runner, while it is still
// identifiable.

/** Archive members are `out/<name>`, and USTAR stores a member name in 100 bytes. */
const MAX_MEMBER_BYTES = 100;
const ARCHIVE_ROOT = "out/";
const PORTABLE_NAME = /^[A-Za-z0-9][A-Za-z0-9._/-]*$/;

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
