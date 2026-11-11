import { isSet } from "./env.ts";

// The repository in the certificate identity cosign expects on a release lock: the
// repository whose release workflow cut the release. Deriving it from the run context
// (GITHUB_REPOSITORY) keeps signing and verification aligned across a repository transfer.
// Outside CI — the operator flow documented in docs/admin/install.mdx — the canonical
// repository remains the fallback; releases signed before a transfer keep the old
// owner/repo in their certificate either way.

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
