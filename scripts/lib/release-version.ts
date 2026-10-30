/** The stable and numbered release-candidate versions that the release train publishes. */
export interface Version {
	readonly major: number;
	readonly minor: number;
	readonly patch: number;
	readonly rc?: number;
}

const version =
	/^(?<major>0|[1-9]\d*)\.(?<minor>0|[1-9]\d*)\.(?<patch>0|[1-9]\d*)(?:-rc\.(?<rc>0|[1-9]\d*))?$/u;

export function parseVersion(value: string): Version | undefined {
	const match = version.exec(value);
	const groups = match?.[0] === value ? match.groups : undefined;
	if (
		groups === undefined ||
		[groups.major, groups.minor, groups.patch, groups.rc].some(
			(part) => part !== undefined && !Number.isSafeInteger(Number(part)),
		)
	) {
		return undefined;
	}
	const core = {
		major: Number(groups.major),
		minor: Number(groups.minor),
		patch: Number(groups.patch),
	};
	return groups.rc === undefined ? core : { ...core, rc: Number(groups.rc) };
}

export function compareVersions(left: Version, right: Version): number {
	const core = left.major - right.major || left.minor - right.minor || left.patch - right.patch;
	if (core !== 0) {
		return core;
	}
	if (left.rc === right.rc) {
		return 0;
	}
	if (left.rc === undefined) {
		return 1;
	}
	if (right.rc === undefined) {
		return -1;
	}
	return left.rc - right.rc;
}

export const stableVersion = (value: Version): string =>
	`${value.major}.${value.minor}.${value.patch}`;

export const formatVersion = (value: Version): string =>
	`${stableVersion(value)}${value.rc === undefined ? "" : `-rc.${value.rc}`}`;

/** A stable release may reuse only a numbered candidate of its exact version core. */
export function isCandidatePromotion(candidate: string, stable: string): boolean {
	const from = parseVersion(candidate.replace(/^v/u, ""));
	const to = parseVersion(stable.replace(/^v/u, ""));
	return (
		from !== undefined &&
		to !== undefined &&
		from.rc !== undefined &&
		to.rc === undefined &&
		stableVersion(from) === stableVersion(to)
	);
}

/** Prereleases never move a stable series or the latest image alias. */
export function imageAliases(value: string): string[] {
	const parsed = parseVersion(value);
	if (parsed === undefined) {
		throw new Error(`not a release version: ${value}`);
	}
	return parsed.rc === undefined ? [value, `${parsed.major}.${parsed.minor}`, "latest"] : [value];
}
