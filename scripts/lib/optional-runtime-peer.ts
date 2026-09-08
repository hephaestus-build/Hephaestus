import { isMap, isScalar, isSeq, parseAllDocuments } from "yaml";

// An unused optional type peer is not a runtime installation (ADR 0037). Keep the raw
// lockfile scan, including comments, and mask only explicitly optional peer-name scalars.
export function maskOptionalRuntimePeerMetadata(
	text: string,
	isRetiredReference: (name: string) => boolean,
): string {
	const ranges: [number, number][] = [];
	for (const document of parseAllDocuments(text)) {
		const [error] = document.errors;
		if (error) throw error;
		const optionalPeers = new Set<string>();
		const packages = document.get("packages");
		if (!isMap(packages)) continue;
		for (const entry of packages.items) {
			if (!isMap(entry.value)) continue;
			const dependencies = entry.value.get("peerDependencies");
			const metadata = entry.value.get("peerDependenciesMeta");
			if (!isMap(dependencies) || !isMap(metadata)) continue;
			for (const dependency of dependencies.items) {
				const key = dependency.key;
				if (!isScalar(key) || typeof key.value !== "string" || !isRetiredReference(key.value))
					continue;
				const peer = key.value;
				if (metadata.getIn([peer, "optional"]) !== true) continue;
				optionalPeers.add(peer);
				const metaKey = metadata.items.find(
					(item) => isScalar(item.key) && item.key.value === peer,
				)?.key;
				for (const scalar of [key, metaKey]) {
					if (isScalar(scalar) && scalar.range) ranges.push([scalar.range[0], scalar.range[1]]);
				}
			}
		}
		if (optionalPeers.size === 0) continue;
		const snapshots = document.get("snapshots");
		if (isMap(snapshots)) {
			for (const entry of snapshots.items) {
				if (!isMap(entry.value)) continue;
				const peers = entry.value.get("transitivePeerDependencies");
				if (!isSeq(peers)) continue;
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
	}
	for (const [start, end] of ranges) {
		text = text.slice(0, start) + " ".repeat(end - start) + text.slice(end);
	}
	return text;
}
