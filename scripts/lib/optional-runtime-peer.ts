import { isMap, isScalar, isSeq, parseAllDocuments, type YAMLMap } from "yaml";

type Range = [number, number];

/** The retired peers a package map declares optional, with the ranges of every scalar naming one. */
function optionalPeerRanges(
	packages: YAMLMap,
	isRetiredReference: (name: string) => boolean,
	ranges: Range[],
): Set<string> {
	const optionalPeers = new Set<string>();
	for (const entry of packages.items) {
		if (!isMap(entry.value)) {
			continue;
		}
		const dependencies = entry.value.get("peerDependencies");
		const metadata = entry.value.get("peerDependenciesMeta");
		if (!isMap(dependencies) || !isMap(metadata)) {
			continue;
		}
		for (const dependency of dependencies.items) {
			const { key } = dependency;
			if (!isScalar(key) || typeof key.value !== "string" || !isRetiredReference(key.value)) {
				continue;
			}
			const peer = key.value;
			if (metadata.getIn([peer, "optional"]) !== true) {
				continue;
			}
			optionalPeers.add(peer);
			const metaKey = metadata.items.find(
				(item) => isScalar(item.key) && item.key.value === peer,
			)?.key;
			for (const scalar of [key, metaKey]) {
				if (isScalar(scalar) && scalar.range) {
					ranges.push([scalar.range[0], scalar.range[1]]);
				}
			}
		}
	}
	return optionalPeers;
}

/** The ranges of every `transitivePeerDependencies` item in a snapshot map naming an optional peer. */
function transitivePeerRanges(
	snapshots: YAMLMap,
	optionalPeers: Set<string>,
	ranges: Range[],
): void {
	for (const entry of snapshots.items) {
		if (!isMap(entry.value)) {
			continue;
		}
		const peers = entry.value.get("transitivePeerDependencies");
		if (!isSeq(peers)) {
			continue;
		}
		for (const item of peers.items) {
			if (
				isScalar(item) &&
				typeof item.value === "string" &&
				optionalPeers.has(item.value) &&
				item.range
			) {
				ranges.push([item.range[0], item.range[1]]);
			}
		}
	}
}

// An unused optional type peer is not a runtime installation (ADR 0037). Keep the raw
// lockfile scan, including comments, and mask only explicitly optional peer-name scalars.
export function maskOptionalRuntimePeerMetadata(
	text: string,
	isRetiredReference: (name: string) => boolean,
): string {
	const ranges: Range[] = [];
	for (const document of parseAllDocuments(text)) {
		const [error] = document.errors;
		if (error) {
			throw error;
		}
		const packages = document.get("packages");
		if (!isMap(packages)) {
			continue;
		}
		const optionalPeers = optionalPeerRanges(packages, isRetiredReference, ranges);
		if (optionalPeers.size === 0) {
			continue;
		}
		const snapshots = document.get("snapshots");
		if (isMap(snapshots)) {
			transitivePeerRanges(snapshots, optionalPeers, ranges);
		}
	}
	let masked = text;
	for (const [start, end] of ranges) {
		masked = masked.slice(0, start) + " ".repeat(end - start) + masked.slice(end);
	}
	return masked;
}
