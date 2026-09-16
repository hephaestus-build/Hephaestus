import { basename, isAbsolute, relative } from "node:path";

import { defineRule } from "@oxlint/plugins";

/**
 * Anything outside printable ASCII. Two classes of character fail here and both are worth failing:
 * a letter that merely looks like an ASCII one (`é`, `—`, a Cyrillic `а`) makes two paths that draw
 * identically compare unequal, and an invisible one (a zero-width space, a control character) hides
 * in the name with nothing on screen to show for it.
 */
const OUTSIDE_ASCII = /[^ -~]/u;

/** Both separators, so the rule reads a Windows path the same way it reads a POSIX one. */
const PATH_SEPARATOR = /[\\/]/u;

/**
 * The part of the path this repo owns. `context.filename` is absolute, so it carries the checkout
 * path with it — and that belongs to whoever cloned the repo, who may well have a name of their own
 * outside ASCII. oxlint runs from the repo root or a package root (`vp -C webapp`); either way
 * `context.cwd` is inside the checkout and what remains is a path this repo owns.
 *
 * When `cwd` turns out not to contain the file, the relative path climbs out through `..` and says
 * nothing about the repo; the file's own name is the part that is still certainly the repo's, so the
 * rule falls back to that rather than to guessing.
 */
function ownedSegments(filename: string, cwd: string): string[] {
	const relativePath = relative(cwd, filename);
	if (
		relativePath === "" ||
		isAbsolute(relativePath) ||
		relativePath.split(PATH_SEPARATOR)[0] === ".."
	) {
		return [basename(filename)];
	}
	return relativePath.split(PATH_SEPARATOR);
}

export const noNonAsciiFilename = defineRule({
	meta: {
		type: "problem",
		docs: {
			description:
				"A path this repo owns is spelled in ASCII. A name outside it survives the author's machine and then stops being one string: a filesystem may store it decomposed while the import states it composed, Git prints it escaped, a zip archive re-encodes it, and a tool that matches paths as text quietly stops matching. `unicorn/filename-case` checks the shape of a name and not its alphabet, so this repository rule enforces the alphabet.",
		},
		messages: {
			nonAscii:
				"`{{segment}}` is outside ASCII, so this path is not one string on every machine, archive and tool that has to match it. Rename it with ASCII letters, digits, `.`, `-` and `_`, as its neighbours are.",
		},
	},
	create(context) {
		return {
			// The finding is the file's, not any expression's, so it is made once, where a reader looking
			// at the report will already have the file open: its first line.
			Program() {
				const offending = ownedSegments(context.filename, context.cwd).find((segment) =>
					OUTSIDE_ASCII.test(segment),
				);
				if (offending === undefined) {
					return;
				}
				context.report({
					messageId: "nonAscii",
					data: { segment: offending },
					loc: { line: 1, column: 0 },
				});
			},
		};
	},
});
