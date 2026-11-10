/**
 * The preflight of an install on a managed cluster without cluster-admin
 * (docs/admin/managed-cluster.mdx). It runs with the operator's own kubectl access, before the install,
 * and needs no right that the install does not need.
 *
 * It renders the chart with profiles/cluster.yaml and the operator's values, so the chart is the one
 * list of what the install creates. Then it checks the cluster:
 *
 * - every API that the rendered objects use is served, the agent-sandbox ones at the schema of the
 *   admin bundle;
 * - the operator may create, read, change and delete each rendered object in its namespace, and holds
 *   every permission of each rendered Role, which Kubernetes requires of whoever creates the Role;
 * - the chart renders no cluster-scoped object;
 * - the controller's ServiceAccount holds exactly the read-only ClusterRole of the admin bundle;
 * - both namespaces exist with the Restricted Pod Security level;
 * - the PriorityClasses, the Gateway listeners and the StorageClass that the values name exist.
 *
 * Each finding is one line that the operator can forward to the cluster's admins. A `missing:` line
 * fails the preflight. An `unverified:` line names a fact that the operator's access cannot read.
 *
 * Isolation (user namespaces, NetworkPolicy enforcement) is not checked here: the worker's isolation
 * probe checks it on the real nodes, and the worker is not ready until the probe passes. `--installed`
 * reads that result after the install.
 *
 *   node scripts/cluster-preflight.ts --namespace <app> --values <operator values.yaml> \
 *     [--values <more.yaml> ...] [--release hephaestus] [--context <kubectl context>] [--installed]
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";

import { parseAllDocuments } from "yaml";

import { PROBE_PASSED } from "./kubernetes-cluster.ts";
import { asArray, asRecord, isRecord } from "./lib/json.ts";
import { output } from "./lib/process.ts";

const ROOT = path.join(import.meta.dirname, "..");
const CHART = path.join(ROOT, "deploy", "chart", "hephaestus");
const PROFILE = path.join(CHART, "profiles", "cluster.yaml");
/** The admin bundle: the CRDs, PriorityClasses and read-only ClusterRole that the admins apply. */
export const ADMIN_BUNDLE = path.join(ROOT, "deploy", "platform", "hephaestus-cluster-admin.yaml");
/** Helm reads, creates, changes and deletes each object of a release. */
const OBJECT_VERBS = ["get", "create", "patch", "delete"] as const;
const BUNDLE_LINE =
	"The admins apply the admin bundle of this release (hephaestus-cluster-admin.yaml).";
const RESTRICTED = "pod-security.kubernetes.io/enforce=restricted";

export type Resource = Record<string, unknown>;

/** One served resource of a group version, as discovery lists it. */
export interface Served {
	name: string;
	kind: string;
	namespaced: boolean;
}

/** The answer to a read: the object, or why there is none. */
export type Read = { found: unknown } | "absent" | "forbidden";

/** What the preflight asks of the cluster; `kubectl` answers in production, a fake in the tests. */
export interface Cluster {
	/** The resources of one apiVersion, or null when the cluster does not serve it. */
	discover: (apiVersion: string) => Promise<readonly Served[] | null>;
	canI: (verb: string, resource: string, namespace: string | null) => Promise<boolean>;
	/** The answer for a ServiceAccount in all namespaces, or null when this access cannot impersonate it. */
	canIAs: (serviceAccount: string, verb: string, resource: string) => Promise<boolean | null>;
	read: (resource: string, name: string, namespace: string | null) => Promise<Read>;
}

export interface Finding {
	kind: "missing" | "unverified";
	text: string;
}

export interface Inputs {
	/** The rendered manifests of the release. */
	manifests: readonly Resource[];
	/** The merged values of the release: the profile, then the operator's files. */
	values: Resource;
	/** The release namespace. */
	namespace: string;
	/** The admin bundle's objects: its CRDs, PriorityClasses, ClusterRole and binding. */
	bundle: readonly Resource[];
}

const missing = (text: string): Finding => ({ kind: "missing", text });
const unverified = (text: string): Finding => ({ kind: "unverified", text });
const text = (value: unknown): string => (typeof value === "string" ? value : "");
function field(value: unknown, ...keys: string[]): unknown {
	let node = value;
	for (const key of keys) {
		node = isRecord(node) ? node[key] : undefined;
	}
	return node;
}
const list = (value: unknown, label: string) => asArray(value ?? [], label);
const nameOf = (object: unknown) => text(field(object, "metadata", "name"));

/** The group of `group/version`, empty for the core group's `v1`. */
function groupOf(apiVersion: string): string {
	return apiVersion.includes("/") ? (apiVersion.split("/")[0] ?? "") : "";
}

/** `pods`, `sandboxclaims.extensions.agents.x-k8s.io`: the form that `kubectl auth can-i` takes. */
const qualified = (name: string, group: string) => (group === "" ? name : `${name}.${group}`);

/** The rules of a Role or ClusterRole, as `verb resource.group` pairs. */
export function grants(role: unknown): string[] {
	return list(field(role, "rules"), "rules").flatMap((rule) =>
		list(field(rule, "apiGroups"), "apiGroups").flatMap((group) =>
			list(field(rule, "resources"), "resources").flatMap((resource) =>
				list(field(rule, "verbs"), "verbs").map(
					(verb) => `${text(verb)} ${qualified(text(resource), text(group))}`,
				),
			),
		),
	);
}

/**
 * The arguments of `kubectl auth can-i` for a resource as `qualified` writes it. kubectl reads a
 * `/` in the resource argument as an object name, so a subresource (`pods/log`,
 * `sandboxes/status.agents.x-k8s.io`) goes to `--subresource`.
 */
export function canIArgs(verb: string, resource: string): string[] {
	const match = /^(?<name>[^/.]+)\/(?<subresource>[^.]+)(?<group>\..+)?$/u.exec(resource);
	const groups = match?.groups;
	if (groups === undefined) {
		return ["auth", "can-i", verb, resource];
	}
	return [
		"auth",
		"can-i",
		verb,
		`${groups.name ?? ""}${groups.group ?? ""}`,
		`--subresource=${groups.subresource ?? ""}`,
	];
}

/** One discovery per group version, shared by every check. */
function discovery(cluster: Cluster) {
	const served = new Map<string, readonly Served[] | null>();
	return async (apiVersion: string) => {
		if (!served.has(apiVersion)) {
			served.set(apiVersion, await cluster.discover(apiVersion));
		}
		return served.get(apiVersion) ?? null;
	};
}
type Discover = ReturnType<typeof discovery>;

/** Every check, in the order that an admin would act on them. */
export async function preflight(inputs: Inputs, cluster: Cluster): Promise<Finding[]> {
	const discover = discovery(cluster);
	return [
		...(await crdChecks(inputs, cluster, discover)),
		...(await objectChecks(inputs, cluster, discover)),
		...(await controllerChecks(inputs, cluster)),
		...(await namespaceChecks(inputs, cluster)),
		...(await priorityChecks(inputs, cluster)),
		...(await gatewayChecks(inputs, cluster)),
		...(await storageChecks(inputs, cluster)),
	];
}

const crdsOf = (inputs: Inputs) =>
	inputs.bundle.filter((object) => object.kind === "CustomResourceDefinition");

/** The agent-sandbox APIs are served, at the schema of the admin bundle. */
async function crdChecks(inputs: Inputs, cluster: Cluster, discover: Discover) {
	const findings: Finding[] = [];
	for (const crd of crdsOf(inputs)) {
		const name = nameOf(crd);
		const plural = text(field(crd, "spec", "names", "plural"));
		const served = list(field(crd, "spec", "versions"), `${name} versions`).filter(
			(version) => field(version, "served") === true,
		);
		for (const version of served) {
			const apiVersion = `${text(field(crd, "spec", "group"))}/${text(field(version, "name"))}`;
			const resources = await discover(apiVersion);
			if (resources?.some((resource) => resource.name === plural) !== true) {
				findings.push(
					missing(`The cluster does not serve ${plural} in ${apiVersion}. ${BUNDLE_LINE}`),
				);
				continue;
			}
			const read = await cluster.read("customresourcedefinitions.apiextensions.k8s.io", name, null);
			if (read === "forbidden") {
				findings.push(
					unverified(
						`The CRD ${name} cannot be read with this access. The admins confirm that it is the one of this release's hephaestus-cluster-admin.yaml.`,
					),
				);
			} else if (read !== "absent" && !sameSchema(crd, read.found, text(field(version, "name")))) {
				findings.push(
					missing(
						`The CRD ${name} differs from the one in this release's hephaestus-cluster-admin.yaml at ${apiVersion}. The admins apply the admin bundle of this release.`,
					),
				);
			}
		}
	}
	return findings;
}

interface Need {
	verb: string;
	resource: string;
	namespace: string;
	why: string;
}

/**
 * What the operator must be allowed for each rendered object, or why the object cannot be installed.
 * Kubernetes lets a user create a Role only with every permission that it grants, or with escalate.
 */
async function needsOf(inputs: Inputs, discover: Discover) {
	const findings: Finding[] = [];
	const needs = new Map<string, Need>();
	const add = (need: Need) => needs.set(`${need.verb} ${need.resource} ${need.namespace}`, need);
	const groups = new Set(crdsOf(inputs).map((crd) => text(field(crd, "spec", "group"))));
	for (const object of inputs.manifests) {
		const apiVersion = text(object.apiVersion);
		const kind = text(object.kind);
		const name = nameOf(object);
		const resources = await discover(apiVersion);
		const resource = resources?.find((entry) => entry.kind === kind);
		if (resource === undefined) {
			if (resources !== null) {
				findings.push(
					missing(`The cluster serves ${apiVersion} but not ${kind}, which the release creates.`),
				);
			} else if (!groups.has(groupOf(apiVersion))) {
				findings.push(
					missing(
						`The cluster does not serve ${apiVersion}, which the release's ${kind} ${name} needs. The admins install the API that provides ${kind}.`,
					),
				);
			}
			continue;
		}
		if (!resource.namespaced) {
			findings.push(
				missing(
					`The release renders the cluster-scoped ${kind} ${name}. Install with profiles/cluster.yaml, which leaves cluster-scoped objects to the admin bundle.`,
				),
			);
			continue;
		}
		const namespace = text(field(object, "metadata", "namespace")) || inputs.namespace;
		for (const verb of OBJECT_VERBS) {
			add({
				verb,
				resource: qualified(resource.name, groupOf(apiVersion)),
				namespace,
				why: `${kind} ${name}`,
			});
		}
		if (kind === "Role") {
			for (const pair of grants(object)) {
				const [verb = "", granted = ""] = pair.split(" ");
				add({ verb, resource: granted, namespace, why: `the Role ${name}` });
			}
		}
	}
	return { findings, needs: [...needs.values()] };
}

/** The operator's rights for each rendered object and each rule of each rendered Role. */
async function objectChecks(inputs: Inputs, cluster: Cluster, discover: Discover) {
	const { findings, needs } = await needsOf(inputs, discover);
	// Escalate lets the operator create the Role; bind lets it create the RoleBinding to it.
	const escalates = new Map<string, boolean>();
	const mayEscalate = async (namespace: string) => {
		const known = escalates.get(namespace);
		if (known !== undefined) {
			return known;
		}
		const answer =
			(await cluster.canI("escalate", "roles.rbac.authorization.k8s.io", namespace)) &&
			(await cluster.canI("bind", "roles.rbac.authorization.k8s.io", namespace));
		escalates.set(namespace, answer);
		return answer;
	};
	for (const { verb, resource, namespace, why } of needs) {
		if (await cluster.canI(verb, resource, namespace)) {
			continue;
		}
		if (why.startsWith("the Role ") && (await mayEscalate(namespace))) {
			continue;
		}
		findings.push(
			missing(
				`The operator may not ${verb} ${resource} in the namespace ${namespace} (${why}). The admins grant it.`,
			),
		);
	}
	return findings;
}

/** The controller's one cluster-scoped grant: exactly the read-only ClusterRole of the admin bundle. */
async function controllerChecks(inputs: Inputs, cluster: Cluster): Promise<Finding[]> {
	const controller = inputs.manifests.find(
		(object) =>
			object.kind === "ServiceAccount" && nameOf(object).endsWith("-agent-sandbox-controller"),
	);
	if (controller === undefined) {
		return [];
	}
	const serviceAccount = nameOf(controller);
	const role = inputs.bundle.find((object) => object.kind === "ClusterRole");
	const roleName = nameOf(role);
	const handoff = `The admins apply the ClusterRole ${roleName} and its ClusterRoleBinding to the ServiceAccount ${inputs.namespace}/${serviceAccount} from the admin bundle (hephaestus-cluster-admin.yaml).`;
	const wanted = new Set(grants(role));
	const lacking: string[] = [];
	for (const pair of wanted) {
		const [verb = "", resource = ""] = pair.split(" ");
		const answer = await cluster.canIAs(
			`system:serviceaccount:${inputs.namespace}:${serviceAccount}`,
			verb,
			resource,
		);
		if (answer === null) {
			return compareGrant(inputs, cluster, { roleName, serviceAccount, wanted, handoff });
		}
		if (!answer) {
			lacking.push(pair);
		}
	}
	return lacking.length === 0
		? []
		: [
				missing(
					`The agent-sandbox controller may not ${lacking.join(", ")} in all namespaces. ${handoff}`,
				),
			];
}

/** Without impersonation: the ClusterRole and its binding must match the admin bundle exactly. */
async function compareGrant(
	inputs: Inputs,
	cluster: Cluster,
	grant: { roleName: string; serviceAccount: string; wanted: ReadonlySet<string>; handoff: string },
): Promise<Finding[]> {
	const { roleName, serviceAccount, wanted, handoff } = grant;
	const role = await cluster.read("clusterroles.rbac.authorization.k8s.io", roleName, null);
	const binding = await cluster.read(
		"clusterrolebindings.rbac.authorization.k8s.io",
		roleName,
		null,
	);
	if (role === "forbidden" || binding === "forbidden") {
		return [
			unverified(
				`The ClusterRole ${roleName} cannot be checked with this access. The admins confirm that it is the one of this release's hephaestus-cluster-admin.yaml, bound to ${inputs.namespace}/${serviceAccount}.`,
			),
		];
	}
	if (role === "absent" || binding === "absent") {
		return [missing(`The ClusterRole ${roleName} or its binding does not exist. ${handoff}`)];
	}
	const held = new Set(grants(role.found));
	const findings: Finding[] = [];
	if (held.size !== wanted.size || [...wanted].some((pair) => !held.has(pair))) {
		findings.push(
			missing(
				`The ClusterRole ${roleName} differs from the read-only one of this release. ${handoff}`,
			),
		);
	}
	const bound =
		field(binding.found, "roleRef", "kind") === "ClusterRole" &&
		field(binding.found, "roleRef", "name") === roleName &&
		list(field(binding.found, "subjects"), "subjects").some(
			(entry) =>
				field(entry, "kind") === "ServiceAccount" &&
				field(entry, "name") === serviceAccount &&
				field(entry, "namespace") === inputs.namespace,
		);
	if (!bound) {
		findings.push(
			missing(
				`The ClusterRoleBinding ${roleName} does not bind ${roleName} to ${inputs.namespace}/${serviceAccount}. ${handoff}`,
			),
		);
	}
	return findings;
}

/** Both namespaces exist, at the Restricted Pod Security level. */
async function namespaceChecks(inputs: Inputs, cluster: Cluster) {
	const sandboxes = text(field(inputs.values, "worker", "sandbox", "namespace"));
	const findings: Finding[] = [];
	for (const namespace of [inputs.namespace, sandboxes].filter((name) => name !== "")) {
		const read = await cluster.read("namespaces", namespace, null);
		if (read === "absent") {
			findings.push(
				missing(
					`The namespace ${namespace} does not exist. The admins create it with the label ${RESTRICTED}.`,
				),
			);
		} else if (read === "forbidden") {
			findings.push(
				unverified(
					`The namespace ${namespace} cannot be read with this access. The admins confirm that it has the label ${RESTRICTED}.`,
				),
			);
		} else if (
			field(read.found, "metadata", "labels", "pod-security.kubernetes.io/enforce") !== "restricted"
		) {
			findings.push(
				missing(`The namespace ${namespace} lacks the label ${RESTRICTED}. The admins add it.`),
			);
		}
	}
	return findings;
}

/** The PriorityClasses that the release names but does not create. */
async function priorityChecks(inputs: Inputs, cluster: Cluster) {
	if (field(inputs.values, "priorityClasses", "create") !== false) {
		return [];
	}
	const findings: Finding[] = [];
	for (const tier of ["roles", "conversations", "reviews"]) {
		const name = text(field(inputs.values, "priorityClasses", tier));
		const read = await cluster.read("priorityclasses.scheduling.k8s.io", name, null);
		if (read === "absent") {
			findings.push(missing(`The PriorityClass ${name} does not exist. ${BUNDLE_LINE}`));
		} else if (read === "forbidden") {
			findings.push(
				unverified(
					`The PriorityClass ${name} cannot be read with this access. The admins confirm that hephaestus-cluster-admin.yaml is applied.`,
				),
			);
		}
	}
	return findings;
}

/** The Gateway listeners that the routes attach to, and whether they accept routes from this namespace. */
async function gatewayChecks(inputs: Inputs, cluster: Cluster) {
	const findings: Finding[] = [];
	for (const parent of list(field(inputs.values, "route", "parentRefs"), "parentRefs")) {
		const name = text(field(parent, "name"));
		const namespace = text(field(parent, "namespace")) || inputs.namespace;
		const section = text(field(parent, "sectionName"));
		const gateway = `${namespace}/${name}`;
		const read = await cluster.read("gateways.gateway.networking.k8s.io", name, namespace);
		if (read === "absent") {
			findings.push(
				missing(
					`The Gateway ${gateway} does not exist. The admins name the Gateway that serves the release's host.`,
				),
			);
			continue;
		}
		if (read === "forbidden") {
			findings.push(
				unverified(
					`The Gateway ${gateway} cannot be read with this access. The admins confirm that its listener ${section || "(any)"} accepts HTTPRoutes from ${inputs.namespace}.`,
				),
			);
			continue;
		}
		const listeners = list(field(read.found, "spec", "listeners"), "listeners").filter(
			(listener) => section === "" || field(listener, "name") === section,
		);
		if (listeners.length === 0) {
			findings.push(
				missing(
					`The Gateway ${gateway} has no listener ${section}. The admins add it, or the operator names another.`,
				),
			);
		}
		findings.push(
			...listeners.flatMap((listener) =>
				listenerFindings(listener, gateway, namespace, inputs.namespace),
			),
		);
	}
	return findings;
}

function listenerFindings(
	listener: unknown,
	gateway: string,
	gatewayNamespace: string,
	namespace: string,
) {
	const from = text(field(listener, "allowedRoutes", "namespaces", "from")) || "Same";
	const name = text(field(listener, "name"));
	if (from === "Same" && gatewayNamespace !== namespace) {
		return [
			missing(
				`The listener ${name} of the Gateway ${gateway} accepts routes from its own namespace only. The admins allow routes from ${namespace}.`,
			),
		];
	}
	if (from === "Selector") {
		return [
			unverified(
				`The listener ${name} of the Gateway ${gateway} selects namespaces by label. The admins confirm that ${namespace} matches.`,
			),
		];
	}
	return [];
}

/** The storage of the bundled PostgreSQL and NATS: the named StorageClass, or a default one. */
async function storageChecks(inputs: Inputs, cluster: Cluster) {
	const classes = new Set(
		["postgresql", "nats"]
			.filter((owner) => field(inputs.values, owner, "bundled") === true)
			.map((owner) => text(field(inputs.values, owner, "persistence", "storageClassName"))),
	);
	if (classes.size === 0) {
		return [];
	}
	const read = await cluster.read("storageclasses.storage.k8s.io", "", null);
	if (read === "forbidden") {
		const named = [...classes].filter((name) => name !== "").join(", ") || "the volumes";
		return [
			unverified(
				`The StorageClasses cannot be read with this access. The admins confirm a ReadWriteOnce class${classes.has("") ? " that is the default" : ""} for ${named}.`,
			),
		];
	}
	const items = read === "absent" ? [] : list(field(read.found, "items"), "storage classes");
	const isDefault = (item: unknown) =>
		field(item, "metadata", "annotations", "storageclass.kubernetes.io/is-default-class") ===
		"true";
	return [...classes].flatMap((wanted) => {
		if (wanted === "") {
			return items.some(isDefault)
				? []
				: [
						missing(
							"The cluster has no default StorageClass. The admins mark one as default, or the operator names one in the values.",
						),
					];
		}
		return items.some((item) => nameOf(item) === wanted)
			? []
			: [
					missing(
						`The StorageClass ${wanted} does not exist. The admins create it, or the operator names another.`,
					),
				];
	});
}

/** Whether the installed CRD serves `version` with the schema of the pinned file. */
export function sameSchema(pinned: Resource, installed: unknown, version: string): boolean {
	const schemaOf = (crd: unknown) =>
		list(field(crd, "spec", "versions"), "versions").find(
			(entry) => field(entry, "name") === version,
		);
	return (
		canonical(field(schemaOf(pinned), "schema")) === canonical(field(schemaOf(installed), "schema"))
	);
}

/** JSON with sorted keys, so that two equal schemas compare equal whatever their key order. */
function canonical(value: unknown): string {
	if (Array.isArray(value)) {
		return `[${value.map(canonical).join(",")}]`;
	}
	if (isRecord(value)) {
		return `{${Object.keys(value)
			.toSorted()
			.map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
			.join(",")}}`;
	}
	return value === undefined ? "undefined" : JSON.stringify(value);
}

/** The YAML documents of a manifest stream that are objects. */
export function documents(source: string): Resource[] {
	return parseAllDocuments(source)
		.map((document) => {
			const value: unknown = document.toJS();
			return value;
		})
		.filter(isRecord);
}

/**
 * What a failed `kubectl get` means. A kind that the cluster does not serve, such as a Gateway without
 * the Gateway API, is absent: its own finding says what the admins install.
 */
export function readFailure(stderr: string, command: string): "absent" | "forbidden" {
	if (/forbidden/iu.test(stderr)) {
		return "forbidden";
	}
	if (/NotFound|not found|doesn't have a resource type/u.test(stderr)) {
		return "absent";
	}
	throw new Error(`${command}: ${stderr.trim()}`);
}

/** The cluster through kubectl, with the operator's own credentials. */
function kubectlCluster(context: string | undefined): Cluster {
	const base = context === undefined ? [] : ["--context", context];
	const kubectl = async (args: string[]) => {
		try {
			return { ok: true as const, stdout: await output("kubectl", [...base, ...args]) };
		} catch (error) {
			return { ok: false as const, stderr: isRecord(error) ? text(error.stderr) : "" };
		}
	};
	return {
		async discover(apiVersion) {
			const result = await kubectl([
				"get",
				"--raw",
				apiVersion.includes("/") ? `/apis/${apiVersion}` : `/api/${apiVersion}`,
			]);
			if (!result.ok) {
				return null;
			}
			return asArray(asRecord(JSON.parse(result.stdout), apiVersion).resources, "resources")
				.filter((entry) => !text(field(entry, "name")).includes("/"))
				.map((entry) => ({
					name: text(field(entry, "name")),
					kind: text(field(entry, "kind")),
					namespaced: field(entry, "namespaced") === true,
				}));
		},
		async canI(verb, resource, namespace) {
			const result = await kubectl([
				...canIArgs(verb, resource),
				...(namespace === null ? [] : ["-n", namespace]),
			]);
			return result.ok;
		},
		async canIAs(serviceAccount, verb, resource) {
			const result = await kubectl([
				...canIArgs(verb, resource),
				"--all-namespaces",
				"--as",
				serviceAccount,
			]);
			if (result.ok) {
				return true;
			}
			// A plain `no` exits 1 with nothing on stderr; a refused impersonation says so.
			return /impersonate/u.test(result.stderr) ? null : false;
		},
		async read(resource, name, namespace) {
			const result = await kubectl([
				"get",
				resource,
				...(name === "" ? [] : [name]),
				...(namespace === null ? [] : ["-n", namespace]),
				"-o",
				"json",
			]);
			if (result.ok) {
				return { found: JSON.parse(result.stdout) as unknown };
			}
			return readFailure(result.stderr, `kubectl get ${resource} ${name}`);
		},
	};
}

/** After the install: the workers are ready only once their isolation probe passed. */
async function probeChecks(
	namespace: string,
	release: string,
	context: string | undefined,
): Promise<Finding[]> {
	const base = context === undefined ? [] : ["--context", context];
	const deployment = `deployment/${release}-worker`;
	try {
		await output("kubectl", [
			...base,
			"-n",
			namespace,
			"rollout",
			"status",
			deployment,
			"--timeout=10m",
		]);
	} catch {
		return [
			missing(
				`The workers of ${namespace}/${release} are not ready. Their log names the isolation probe gate that failed: kubectl -n ${namespace} logs ${deployment}.`,
			),
		];
	}
	const log = await output("kubectl", [...base, "-n", namespace, "logs", deployment]);
	return log.includes(PROBE_PASSED)
		? []
		: [missing(`The worker ${namespace}/${release} reports no passed isolation probe.`)];
}

/** Helm's merge of values files: later maps override earlier keys, everything else replaces. */
export function mergeValues(files: readonly string[]): Resource {
	const merge = (base: Resource, layer: Resource): Resource => {
		const result: Resource = { ...base };
		for (const [key, value] of Object.entries(layer)) {
			const current = result[key];
			result[key] = isRecord(current) && isRecord(value) ? merge(current, value) : value;
		}
		return result;
	};
	let values = documents(readFileSync(path.join(CHART, "values.yaml"), "utf8"))[0] ?? {};
	for (const file of files) {
		values = merge(values, documents(readFileSync(file, "utf8"))[0] ?? {});
	}
	return values;
}

async function main() {
	const { values } = parseArgs({
		options: {
			namespace: { type: "string" },
			values: { type: "string", multiple: true, default: [] },
			release: { type: "string", default: "hephaestus" },
			context: { type: "string" },
			installed: { type: "boolean", default: false },
		},
	});
	if (values.namespace === undefined || values.values.length === 0) {
		throw new Error("--namespace and at least one --values file are required");
	}
	const files = [PROFILE, ...values.values];
	const rendered = await output("helm", [
		"template",
		values.release,
		CHART,
		"--namespace",
		values.namespace,
		...files.flatMap((file) => ["--values", file]),
	]);
	const findings = [
		...(await preflight(
			{
				manifests: documents(rendered),
				values: mergeValues(files),
				namespace: values.namespace,
				bundle: documents(readFileSync(ADMIN_BUNDLE, "utf8")),
			},
			kubectlCluster(values.context),
		)),
		...(values.installed
			? await probeChecks(values.namespace, values.release, values.context)
			: []),
	];
	for (const finding of findings) {
		process.stdout.write(`${finding.kind}: ${finding.text}\n`);
	}
	const failed = findings.filter((finding) => finding.kind === "missing").length;
	const confirm =
		findings.length > 0 ? `, with ${String(findings.length)} facts for the admins to confirm` : "";
	process.stdout.write(
		failed === 0
			? `The preflight of ${values.namespace} passed${confirm}.\n`
			: `The preflight of ${values.namespace} found ${String(failed)} missing items.\n`,
	);
	process.exitCode = failed === 0 ? 0 : 1;
}

if (import.meta.main) {
	await main();
}
