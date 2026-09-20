import { isSet } from "./env.ts";

// The certificate identity cosign expects on a release lock: the release workflow of
// the repository that cut the release, pinned to its default branch. Deriving it from
// the run context (GITHUB_SERVER_URL / GITHUB_REPOSITORY) keeps signing and
// verification aligned automatically across a repository transfer (issue #1599).
// Outside CI — the operator flow documented in docs/admin/install.mdx — the canonical
// repository remains the fallback; releases signed before a transfer keep the old
// owner/repo in their certificate either way.

const FALLBACK_SERVER_URL = "https://github.com";
const FALLBACK_REPOSITORY = "hephaestus-build/Hephaestus";

export function releaseSignerRepository(environment: NodeJS.ProcessEnv): string {
	const repository = environment.GITHUB_REPOSITORY;
	if (isSet(repository)) {
		return repository;
	}
	if (isSet(environment.CI)) {
		throw new Error(
			"GITHUB_REPOSITORY is not set. CI must provide the run's own repository so " +
				"release-lock verification follows the repository identity instead of a stale literal.",
		);
	}
	return FALLBACK_REPOSITORY;
}

export function releaseSignerIdentity(environment: NodeJS.ProcessEnv): string {
	const serverUrl = environment.GITHUB_SERVER_URL ?? FALLBACK_SERVER_URL;
	return `${serverUrl}/${releaseSignerRepository(environment)}/.github/workflows/release.yml@refs/heads/main`;
}
