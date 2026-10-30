import { readFile, writeFile } from "node:fs/promises";

import { isSet } from "./lib/env.ts";

import { isRelease } from "./release-image-lock.ts";

const MAX_BODY_BYTES = 125_000;

interface ReleaseNotes {
	notes: string;
	release: string;
	commit: string;
	repository: string;
	migrations: boolean;
}

/** Keep complete notes in the signed asset when GitHub cannot accept them in its description. */
export function githubReleaseBody(options: ReleaseNotes): string {
	const { notes, release, commit, repository, migrations } = options;
	if (
		!isRelease(release) ||
		!/^[a-f0-9]{40}$/u.test(commit) ||
		!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u.test(repository)
	) {
		throw new Error("Release notes need a release tag, a full commit SHA, and an owner/repository");
	}
	const source = `https://github.com/${repository}/blob/${commit}`;
	const warning = migrations
		? `> [!WARNING]
> This release contains **schema migrations**. Before upgrading, complete the release-specific steps in the [migration guide](${source}/MIGRATION.md) and verify your database backup.

`
		: "";
	const prefix = `<!-- hephaestus:schema-migrations=${migrations} -->
${warning}`;
	const body = `${prefix}${notes}`;
	// A byte limit is conservative for Unicode and keeps the description within GitHub's character limit.
	if (Buffer.byteLength(body, "utf8") <= MAX_BODY_BYTES) {
		return body;
	}
	return `${prefix}The complete release notes exceed GitHub's description limit. [Download the full notes](https://github.com/${repository}/releases/download/${release}/release-notes.md), or [read the changelog at the released commit](${source}/CHANGELOG.md).
`;
}

if (import.meta.main) {
	const [notes, body] = process.argv.slice(2);
	const migrations = process.env.MIGRATIONS;
	if (!isSet(notes) || !isSet(body) || (migrations !== "true" && migrations !== "false")) {
		throw new Error(
			"usage: RELEASE=tag COMMIT=sha MIGRATIONS=true|false GITHUB_REPOSITORY=owner/repo github-release-notes.ts <notes-file> <body-file>",
		);
	}
	await writeFile(
		body,
		githubReleaseBody({
			notes: await readFile(notes, "utf8"),
			release: process.env.RELEASE ?? "",
			commit: process.env.COMMIT ?? "",
			repository: process.env.GITHUB_REPOSITORY ?? "",
			migrations: migrations === "true",
		}),
	);
}
