/** New captures declare their inventory contract. Signed archives keep the contract they published. */
export function requiresSbomInventory(
	manifest: Record<string, unknown>,
	field: "bundledNpmSbom" | "sourceSbom",
	capture: boolean,
): boolean {
	const version = manifest[field];
	if (version !== undefined && version !== 1) {
		throw new Error(`unsupported ${field} inventory version`);
	}
	if (capture && version !== 1) {
		throw new Error(`capture manifest must declare ${field}: 1`);
	}
	return version === 1;
}
