/**
 * The inventory contract that a signed evidence manifest declares, per field. `bundledNpmSbom: 1` evidences the
 * bundled npm packages in the nginx `webapp` image; `bundledNpmSbom: 2` evidences them in the web app that the
 * server image carries (ADR 0100). A capture declares the current version; an archived release keeps its own.
 */
const CURRENT = { bundledNpmSbom: 2, sourceSbom: 1 } as const;

/** The declared version of `field`, or 0 for a release that predates it. */
export function sbomInventoryVersion(
	manifest: Record<string, unknown>,
	field: keyof typeof CURRENT,
	capture: boolean,
): number {
	const version = manifest[field];
	if (
		version !== undefined &&
		!(
			typeof version === "number" &&
			Number.isInteger(version) &&
			version >= 1 &&
			version <= CURRENT[field]
		)
	) {
		throw new Error(`unsupported ${field} inventory version`);
	}
	if (capture && version !== CURRENT[field]) {
		throw new Error(`capture manifest must declare ${field}: ${CURRENT[field]}`);
	}
	return typeof version === "number" ? version : 0;
}
