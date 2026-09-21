// Changelog entries are the summary verbatim — no commit hashes, PR links, or
// attribution (the default formatter prefixes a commit hash; changelog-github
// adds PR links + "Thanks @user!"). Operators read this file; git metadata
// lives in git.
module.exports = {
	/** @param {{ summary: string }} changeset The changeset whose summary becomes the entry. */
	getReleaseLine: (changeset) => `- ${changeset.summary.trim().split("\n").join("\n  ")}`,
	getDependencyReleaseLine: () => "",
};
