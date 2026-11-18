import path from "node:path";

/** Shared Syft configuration for file metadata and SHA-256 digests. */
export const SYFT_CONFIG = path.join(import.meta.dirname, "..", "..", "security", "syft.yaml");

/**
 * Syft reading one platform manifest from its registry. A daemon pull would re-serialize an OCI manifest
 * as Docker schema 2 and record a locally computed digest; `--platform` makes Syft fail loudly if the
 * reference is not this platform's.
 */
export function syftImageArguments(reference: string, platform: string): string[] {
	return [
		"--from",
		"registry",
		reference,
		"--platform",
		platform,
		"--scope",
		"squashed",
		"--config",
		SYFT_CONFIG,
	];
}
