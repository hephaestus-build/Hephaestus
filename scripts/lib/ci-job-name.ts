/** Map historical run names to the current sidebar vocabulary for evidence and time-window reports. */
export function canonicalCiJobName(name: string): string {
	const renamed: Record<string, string> = {
		"App Server: Package": "Build / Server package",
		"Build / App Server: Generated artifacts": "Build / Generated API",
		"Build / App Server: Database": "Build / Database migrations",
		"Build / App Server: PostgreSQL drills": "Build / PostgreSQL drills",
		"Build / Webapp: E2E": "Browser tests / Webapp",
		"Build / Extension: E2E": "Browser tests / Extension",
		"Quality / Webapp: Stories": "Quality / Webapp stories",
		"Quality / App Server / Gates": "Quality / Server",
		"Quality / Webapp / Gates": "Quality / Webapp",
		"Docker / Images / Create manifests": "Images / Manifest",
		"Security / Dependencies, secrets, and policy": "Security / Dependencies, secrets and policy",
	};
	if (renamed[name] !== undefined) return renamed[name];
	const tier =
		/^Test(?: \((?<shard>unit|architecture)\))? \/ App Server: (?<tier>Unit|Architecture|Unit and architecture)$/u.exec(
			name,
		);
	if (tier?.groups?.tier !== undefined) {
		const label = tier.groups.tier;
		if (
			tier.groups.shard === undefined ||
			tier.groups.shard === label.toLowerCase() ||
			(tier.groups.shard === "unit" && label === "Unit and architecture")
		) {
			return `Server tests / ${label}`;
		}
	}
	const integration =
		/^Test(?: \((?<caller>[a-z0-9-]+)\))? \/ App Server: Integration \((?<shard>[a-z0-9-]+)\)$/u.exec(
			name,
		);
	if (
		integration?.groups?.shard !== undefined &&
		(integration.groups.caller === undefined ||
			integration.groups.caller === integration.groups.shard)
	) {
		const shard = integration.groups.shard;
		return `Server tests / Integration (${/^\d+$/u.test(shard) ? `${shard} of 8` : shard})`;
	}
	const image =
		/^Docker \/ Images(?: \(linux\/(?<caller>amd64|arm64)\))? \/ Build linux\/(?<arch>amd64|arm64) images$/u.exec(
			name,
		);
	if (
		image?.groups?.arch !== undefined &&
		(image.groups.caller === undefined || image.groups.caller === image.groups.arch)
	) {
		return `Images / linux/${image.groups.arch}`;
	}
	return name;
}
