/** Render the isolated replay provider. Applying and deleting it remain explicit operator steps. */
import { readFile, writeFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import { parseReplay } from "./lib/capacity-replay.ts";
import { parseJson } from "./lib/json.ts";

const { values } = parseArgs({
	options: {
		namespace: { type: "string", default: "capacity-bench-20261030" },
		replay: { type: "string" },
		out: { type: "string" },
		"tool-mode": { type: "string", default: "mixed-read" },
	},
});
if (
	!/^capacity-bench-[a-z0-9-]+$/u.test(values.namespace) ||
	values.replay === undefined ||
	values.replay === "" ||
	values.out === undefined ||
	values.out === ""
) {
	throw new Error("Use a capacity-bench-* namespace and supply --replay and --out");
}
if (!["none", "mixed-read"].includes(values["tool-mode"])) {
	throw new Error("--tool-mode must be none|mixed-read");
}
const replay = parseReplay(parseJson(await readFile(values.replay, "utf8")));
const data: Record<string, string> = { "replay.json": JSON.stringify(replay) };
for (const file of ["capacity-provider.ts", "lib/capacity-replay.ts", "lib/json.ts"]) {
	data[file.replaceAll("/", "__")] = await readFile(new URL(file, import.meta.url), "utf8");
}
const labels = { "app.kubernetes.io/name": "capacity-replay" };
const metadata = { namespace: values.namespace, labels };
await writeFile(
	values.out,
	JSON.stringify(
		{
			apiVersion: "v1",
			kind: "List",
			items: [
				{
					apiVersion: "v1",
					kind: "Namespace",
					metadata: {
						name: values.namespace,
						labels: {
							"pod-security.kubernetes.io/enforce": "restricted",
							"capacity-bench": "synthetic",
						},
					},
				},
				{ apiVersion: "v1", kind: "ConfigMap", metadata: { ...metadata, name: "replay" }, data },
				{
					apiVersion: "apps/v1",
					kind: "Deployment",
					metadata: { ...metadata, name: "replay" },
					spec: {
						replicas: 1,
						selector: { matchLabels: labels },
						template: {
							metadata: { labels },
							spec: {
								automountServiceAccountToken: false,
								securityContext: {
									runAsNonRoot: true,
									runAsUser: 1000,
									seccompProfile: { type: "RuntimeDefault" },
								},
								containers: [
									{
										name: "replay",
										image:
											"node:24.20.0-trixie-slim@sha256:50c3b2f6988dfc307b86e5301d69611af31f4789bdf232863b07d3b02fe55ae0",
										command: [
											"node",
											"/app/capacity-provider.ts",
											"--replay",
											"/app/replay.json",
											"--tool-mode",
											values["tool-mode"],
										],
										ports: [{ containerPort: 8080 }],
										securityContext: {
											allowPrivilegeEscalation: false,
											readOnlyRootFilesystem: true,
											capabilities: { drop: ["ALL"] },
										},
										resources: {
											requests: { cpu: "100m", memory: "64Mi" },
											limits: { cpu: "1", memory: "256Mi" },
										},
										readinessProbe: { httpGet: { path: "/health", port: 8080 } },
										volumeMounts: [{ name: "source", mountPath: "/app", readOnly: true }],
									},
								],
								volumes: [
									{
										name: "source",
										configMap: {
											name: "replay",
											items: [
												...Object.keys(data)
													.filter((key) => key !== "replay.json")
													.map((key) => ({ key, path: key.replaceAll("__", "/") })),
												{ key: "replay.json", path: "replay.json" },
											],
										},
									},
								],
							},
						},
					},
				},
				{
					apiVersion: "v1",
					kind: "Service",
					metadata: { ...metadata, name: "replay" },
					spec: {
						selector: labels,
						ports: [{ port: 8080, targetPort: 8080 }],
					},
				},
				{
					apiVersion: "networking.k8s.io/v1",
					kind: "NetworkPolicy",
					metadata: { ...metadata, name: "replay" },
					spec: {
						podSelector: { matchLabels: labels },
						policyTypes: ["Ingress", "Egress"],
						egress: [],
						ingress: [
							{
								from: [
									{
										namespaceSelector: {
											matchLabels: { "kubernetes.io/metadata.name": values.namespace },
										},
									},
								],
								ports: [{ protocol: "TCP", port: 8080 }],
							},
						],
					},
				},
			],
		},
		null,
		2,
	),
	{ flag: "wx" },
);
