/** Names before and after matrices moved to reusable callers share one verification identity. */
export function canonicalCiJobName(name: string): string {
	if (name === "Test (unit) / App Server: Unit and architecture") {
		return "Test / App Server: Unit and architecture";
	}
	const integration = /^Test \((?<shard>[a-z-]+)\) \/ App Server: Integration \(\k<shard>\)$/u.exec(
		name,
	);
	if (integration?.groups?.shard !== undefined) {
		return `Test / App Server: Integration (${integration.groups.shard})`;
	}
	const image =
		/^Docker \/ Images \(linux\/(?<arch>amd64|arm64)\) \/ Build linux\/\k<arch> images$/u.exec(
			name,
		);
	if (image?.groups?.arch !== undefined) {
		return `Docker / Images / Build linux/${image.groups.arch} images`;
	}
	return name;
}
