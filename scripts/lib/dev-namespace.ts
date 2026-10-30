import { asRecord, parseJson } from "./json.ts";
import { output } from "./process.ts";

export const DEV_OWNER = "hephaestus.build/dev-worktree";

async function namespaceMetadata(
	kubeconfig: string,
	namespace: string,
): Promise<Record<string, unknown> | undefined> {
	const text = await output("kubectl", [
		"--kubeconfig",
		kubeconfig,
		"--context",
		"k3d-hephaestus",
		"get",
		"namespace",
		namespace,
		"--ignore-not-found",
		"-o",
		"json",
	]);
	if (text.trim() === "") {
		return undefined;
	}
	return asRecord(asRecord(parseJson(text), "namespace").metadata, "namespace metadata");
}

export async function ownsDevNamespace(
	kubeconfig: string,
	namespace: string,
	owner: string,
): Promise<boolean> {
	const metadata = await namespaceMetadata(kubeconfig, namespace);
	if (metadata === undefined) {
		return false;
	}
	const annotations = asRecord(metadata.annotations ?? {}, "namespace annotations");
	if (annotations[DEV_OWNER] === undefined) {
		return false;
	}
	if (annotations[DEV_OWNER] !== owner) {
		throw new Error(
			`Namespace ${namespace} belongs to another worktree. Stop it from its owning worktree before reusing this port set or qualification release.`,
		);
	}
	return true;
}

/** Creation and reclaim use optimistic concurrency; a checkout cannot replace another owner. */
export async function claimDevNamespace(
	kubeconfig: string,
	namespace: string,
	owner: string,
	labels: Record<string, string>,
): Promise<void> {
	if (await ownsDevNamespace(kubeconfig, namespace, owner)) {
		return;
	}
	const resource = {
		apiVersion: "v1",
		kind: "Namespace",
		metadata: { name: namespace, labels, annotations: { [DEV_OWNER]: owner } },
	};
	const metadata = await namespaceMetadata(kubeconfig, namespace);
	const args = ["--kubeconfig", kubeconfig, "--context", "k3d-hephaestus"];
	const claim =
		metadata === undefined
			? output("kubectl", [...args, "create", "-f", "-"], { input: JSON.stringify(resource) })
			: output("kubectl", [
					...args,
					"annotate",
					"namespace",
					namespace,
					`${DEV_OWNER}=${owner}`,
					"--overwrite=false",
					`--resource-version=${String(metadata.resourceVersion)}`,
				]);
	await claim.catch(async () => {
		if (!(await ownsDevNamespace(kubeconfig, namespace, owner))) {
			throw new Error(
				"The local namespace could not be claimed; another dev command may be using it",
			);
		}
	});
}
