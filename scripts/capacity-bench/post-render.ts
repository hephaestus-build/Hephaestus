/** TEST ONLY. Isolate a capacity lab; never use this post-renderer for an operator install. */
import { readFile } from "node:fs/promises";
import { isIP } from "node:net";

import { parse, parseAllDocuments, stringify } from "yaml";

// oxlint-disable-next-line import/no-relative-parent-imports -- The test-only Helm entrypoint shares the repository's validated JSON reader.
import { asArray, asRecord, isRecord, parseJson } from "../lib/json.ts";

const namespace = "capacity-bench-20261030";
if (process.env.HELM_NAMESPACE !== namespace) {
	throw new Error("Test-only renderer requires HELM_NAMESPACE=capacity-bench-20261030");
}
const apiCidr = process.env.BENCH_KUBERNETES_API_CIDR;
if (apiCidr === undefined || !apiCidr.endsWith("/32") || isIP(apiCidr.slice(0, -3)) !== 4) {
	throw new Error(
		"Supply the benchmark Kubernetes API's single IPv4 /32 as BENCH_KUBERNETES_API_CIDR",
	);
}
const production = asRecord(
	parse(
		await readFile(
			new URL("../../server/application/src/main/resources/application-prod.yml", import.meta.url),
			"utf8",
		),
	),
	"prod properties",
);
const chunks: Buffer[] = [];
for await (const chunk of process.stdin) {
	chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
}
const resources = parseAllDocuments(Buffer.concat(chunks).toString("utf8")).map((document) => {
	if (document.errors.length > 0) {
		throw new Error("Invalid Helm manifest");
	}
	return asRecord(parseJson(JSON.stringify(document.toJSON())), "resource");
});
if (
	!resources.some(
		(resource) =>
			resource.kind === "PriorityClass" &&
			String(asRecord(resource.metadata, "metadata").name).startsWith(`${namespace}-`),
	)
) {
	throw new Error("Lab PriorityClasses are required to verify Helm's rendered release namespace");
}
for (const resource of resources) {
	const metadata = asRecord(resource.metadata, "metadata");
	if (
		typeof metadata.namespace === "string" &&
		metadata.namespace !== namespace &&
		metadata.namespace !== `${namespace}-sandboxes`
	) {
		throw new Error("Post-renderer refuses another namespace");
	}
	if (resource.kind === "HTTPRoute") {
		continue;
	}
	if (resource.kind === "PriorityClass") {
		if (typeof metadata.name !== "string" || !metadata.name.startsWith(`${namespace}-`)) {
			throw new Error("Post-renderer refuses a priority class outside the lab");
		}
		const priorities = new Map([
			["roles", 900],
			["conversations", 800],
			["reviews", 700],
		]);
		const priority = priorities.get(metadata.name.split("-").at(-1) ?? "");
		if (priority === undefined) {
			throw new Error("Unknown benchmark priority tier");
		}
		resource.value = priority;
		resource.preemptionPolicy = "Never";
		resource.globalDefault = false;
	}
	if (resource.kind === "Deployment") {
		const spec = asRecord(resource.spec, "spec");
		const template = asRecord(spec.template, "template");
		const templateMetadata = asRecord(template.metadata, "template.metadata");
		const labels = asRecord(templateMetadata.labels, "labels");
		const role = labels["app.kubernetes.io/component"];
		if (role === "server" || role === "worker") {
			const pod = asRecord(template.spec, "pod");
			const container = asRecord(asArray(pod.containers, "containers")[0], "container");
			const env = asArray(container.env, "env").map((value) => asRecord(value, "env"));
			const profile = env.find((value) => value.name === "SPRING_PROFILES_ACTIVE");
			if (!profile) {
				throw new Error("Chart profile missing");
			}
			profile.value = role === "server" ? "e2e" : "e2e,worker";
			// JSON has higher precedence than packaged profiles. All prod settings remain exact,
			// except the explicitly authorized synthetic auth/cookie/origin differences.
			const explicit = structuredClone(production);
			const hephaestus = asRecord(explicit.hephaestus, "hephaestus");
			asRecord(hephaestus.workspace, "workspace")["creation-policy"] = "ADMIN_ONLY";
			hephaestus.dev = { "trigger-enabled": `\${HEPHAESTUS_DEV_TRIGGER_ENABLED:false}` };
			hephaestus.llm = {
				egress: { "allow-loopback": `\${HEPHAESTUS_LLM_EGRESS_ALLOW_LOOPBACK:false}` },
			};
			hephaestus.security = {
				"encryption-key": `\${HEPHAESTUS_SECURITY_ENCRYPTION_KEY}`,
				"credential-encryption-key": `\${HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY}`,
			};
			asRecord(hephaestus.agent, "agent").enabled = `\${AGENT_ENABLED:false}`;
			// The prod profile turns NATS on for the server role; the worker role has no broker.
			asRecord(asRecord(hephaestus.sync, "sync").nats, "nats").enabled = role === "server";
			asRecord(hephaestus.integration, "integration").gitlab = {
				enabled: `\${GITLAB_ENABLED:false}`,
			};
			const auth = asRecord(hephaestus.auth, "auth");
			auth["browser-extension-ids"] = `\${HEPHAESTUS_AUTH_BROWSER_EXTENSION_IDS:}`;
			auth.issuer = "http://localhost:8080";
			auth["dev-login-enabled"] = true;
			auth["cookie-secure"] = false;
			auth["cookie-name"] = "HEPHAESTUS_AT";
			if (role === "server") {
				asRecord(explicit.server, "server").address = "127.0.0.1";
			}
			env.push({ name: "SPRING_APPLICATION_JSON", value: JSON.stringify(explicit) });
			container.env = env;
			if (role === "server") {
				pod.containers = [
					...asArray(pod.containers, "containers"),
					{
						name: "driver",
						image:
							"node:24.22.1-trixie-slim@sha256:89c5244bc22a9bc3287d89ebc7e49b9cb5bb84cd40e6175ac3421d5a9f68b95a",
						command: ["node", "-e", "setInterval(() => {}, 2147483647)"],
						securityContext: {
							runAsUser: 1000,
							runAsNonRoot: true,
							allowPrivilegeEscalation: false,
							readOnlyRootFilesystem: true,
							capabilities: { drop: ["ALL"] },
						},
						resources: {
							requests: { cpu: "100m", memory: "64Mi" },
							limits: { cpu: "1", memory: "512Mi" },
						},
						volumeMounts: [{ name: "driver", mountPath: "/bench" }],
					},
				];
				pod.volumes = [
					...asArray(pod.volumes ?? [], "volumes"),
					{ name: "driver", emptyDir: { sizeLimit: "128Mi" } },
				];
			}
		}
	}
	if (resource.kind === "NetworkPolicy") {
		const spec = asRecord(resource.spec, "spec");
		// A missing peer means every destination in Kubernetes. Retain only named lab paths.
		for (const direction of ["ingress", "egress"]) {
			const peerKey = direction === "ingress" ? "from" : "to";
			spec[direction] = asArray(spec[direction] ?? [], direction).filter((rule) => {
				const peers = asArray(asRecord(rule, "rule")[peerKey] ?? [], "peers");
				const ports = asArray(asRecord(rule, "rule").ports ?? [], "ports").map((port) =>
					asRecord(port, "port"),
				);
				if (ports.some((port) => port.endPort !== undefined)) {
					return false;
				}
				return (
					peers.length > 0 &&
					peers.every((peer) => {
						const entry = asRecord(peer, "peer");
						if (entry.ipBlock !== undefined) {
							return (
								direction === "egress" &&
								asRecord(entry.ipBlock, "ipBlock").cidr === apiCidr &&
								ports.length > 0 &&
								ports.every((port) => port.port === 6443 && (port.protocol ?? "TCP") === "TCP")
							);
						}
						if (entry.namespaceSelector !== undefined) {
							const match = asRecord(
								asRecord(entry.namespaceSelector, "namespaceSelector").matchLabels,
								"matchLabels",
							);
							if (
								Object.keys(match).length !== 1 ||
								asRecord(entry.namespaceSelector, "namespaceSelector").matchExpressions !==
									undefined
							) {
								return false;
							}
							const target = match["kubernetes.io/metadata.name"];
							if (target === namespace || target === `${namespace}-sandboxes`) {
								return true;
							}
							if (
								target !== "kube-system" ||
								direction !== "egress" ||
								!isRecord(entry.podSelector)
							) {
								return false;
							}
							const labels = asRecord(entry.podSelector.matchLabels, "DNS pod labels");
							return (
								labels["k8s-app"] === "kube-dns" &&
								ports.length > 0 &&
								ports.every(
									(port) => port.port === 53 && ["TCP", "UDP"].includes(String(port.protocol)),
								)
							);
						}
						return isRecord(entry.podSelector);
					})
				);
			});
		}
	}
	process.stdout.write(`---\n${stringify(resource)}`);
}
