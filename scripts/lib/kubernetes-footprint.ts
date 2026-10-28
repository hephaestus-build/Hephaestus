import { asArray, asRecord, asString } from "./json.ts";

/** Keep both initialization and runtime metadata across samples, including Pending and ended Pods. */
export function parseContainerIdentities(value: unknown) {
	const identities: Record<
		string,
		{
			namespace: string;
			pod: string;
			container: string;
			kind: "initContainers" | "containers";
			image: unknown;
			resources: unknown;
			mounts: unknown;
			volumes: unknown;
		}
	> = {};
	const pods = asRecord(value, "podList");
	for (const podValue of asArray(pods.items, "pods.items")) {
		const pod = asRecord(podValue, "pod");
		const metadata = asRecord(pod.metadata, "metadata");
		const spec = asRecord(pod.spec, "spec");
		const podStatus = asRecord(pod.status, "status");
		for (const [kind, statusKey] of [
			["initContainers", "initContainerStatuses"],
			["containers", "containerStatuses"],
		] as const) {
			for (const statusValue of asArray(podStatus[statusKey] ?? [], statusKey)) {
				const status = asRecord(statusValue, "containerStatus");
				if (typeof status.containerID !== "string") {
					continue;
				}
				// Staging k3s and the cgroup sampler both use containerd IDs.
				const id = status.containerID.replace("containerd://", "");
				const container = asArray(spec[kind] ?? [], kind)
					.map((entry) => asRecord(entry, "container"))
					.find((entry) => entry.name === status.name);
				identities[id] = {
					namespace: asString(metadata.namespace, "namespace"),
					pod: asString(metadata.name, "pod.name"),
					container: asString(status.name, "container.name"),
					kind,
					image: container?.image,
					resources: container?.resources,
					mounts: container?.volumeMounts,
					// Projected credential values and environment values never enter the report.
					volumes: asArray(spec.volumes ?? [], "volumes")
						.map((entry) => asRecord(entry, "volume"))
						.filter((entry) => entry.emptyDir !== undefined),
				};
			}
		}
	}
	return identities;
}
