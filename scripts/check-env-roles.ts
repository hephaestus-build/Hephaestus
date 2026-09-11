/**
 * A setting must reach a container that can actually read it.
 *
 * Runtime roles (`hephaestus.runtime.<role>.enabled`, ADR 0005 / ADR 0008) decide which beans exist
 * in a container. A `@ConfigurationProperties` record read only by role-gated beans is, on a
 * container running with that role off, a variable nothing binds: Compose sets it, `docker inspect`
 * shows it, the documented procedure quotes it, and it configures nothing.
 *
 * Five shapes of that one defect, and this file is the only place they are named:
 *
 *   misdelivered — a service sets a variable whose owning role that same service disables.
 *   undelivered  — the deployment forwards the variable somewhere, but no container running the
 *                  owning role receives it. This is the half that stays broken after a misdelivery
 *                  is removed from the wrong service and never added to the right one.
 *   unforwarded  — `application.yml` offers the knob as `${VAR:default}` and no service forwards it
 *                  at all, so setting it in `.env` does nothing. A setting that is not meant to be
 *                  operator-tunable is written without a placeholder and is never in scope here.
 *   disagreed    — application containers spell one ungated setting differently. Every one of them
 *                  reads it, so the value one is handed is a claim about the whole deployment, and
 *                  two claims describe a stack that does not exist.
 *   omitted      — the same defect with the key left out rather than left blank. A container that
 *                  never mentions a setting makes no claim to disagree with, so `DEPLOYMENT_WIDE`
 *                  names the settings whose absence is itself the failure.
 *
 * `ROLE_SCOPES` and `DEPLOYMENT_WIDE` are the two things to extend, and they are opposite claims
 * about the same question — which containers read a setting. Both are keyed on `application.yml`
 * paths rather than on whole property records because ownership is finer than a record:
 * `hephaestus.webhook.secret` is read on the server role (outbound registration) while
 * `hephaestus.webhook.stream.*` is read on the webhook role, out of the same `WebhookProperties`.
 * An entry naming a path `application.yml` no longer has fails too, so a rename cannot leave a dead
 * entry behind that silently checks nothing.
 *
 * Compose is read with the `yaml` library rather than through `docker compose config`, which would
 * be a stricter parse: this gate runs in `vp run check` and the pre-push hook, where a Docker daemon
 * is not a given, and `config` interpolates from the ambient environment, so its verdict would
 * depend on whose shell it ran in. What the shipped topology does with nothing set is the question,
 * so the `${VAR:-default}` defaults are what gets evaluated. A Compose file that yields no services
 * fails rather than passes.
 */
import { readFile } from "node:fs/promises";
import { join, resolve } from "node:path";

import { parse, parseAllDocuments } from "yaml";

import { isRecord } from "./lib/json.ts";

/** Resolved from this file, so the gate answers the same whatever the working directory is. */
const REPO_ROOT = resolve(import.meta.dirname, "..");
const APPLICATION_YML = "server/application/src/main/resources/application.yml";
/** The production topology. Both files together are one deployment, split by role. */
const COMPOSE_FILES = ["docker/compose.app.yaml", "docker/compose.core.yaml"];

/** The runtime roles a container can be given. A scope claiming any other name does not compile. */
type Role = "server" | "worker" | "webhook";

interface RoleScope {
	readonly path: string;
	readonly role: Role;
	/** Named beans, so a reader can check the claim rather than take it. Quoted back in every failure. */
	readonly why: string;
}

/**
 * `application.yml` paths whose beans exist on one runtime role only. Anything not listed is assumed
 * readable everywhere, which is the safe default: it can only miss a check, never invent one.
 */
const ROLE_SCOPES: readonly RoleScope[] = [
	{
		path: "hephaestus.sandbox.docker",
		role: "worker",
		why: "DockerSandboxConfiguration registers Docker connection, network and runtime settings only when hephaestus.runtime.worker.enabled is active",
	},
	{
		path: "hephaestus.sandbox.gateway.port",
		role: "worker",
		why: "SandboxGatewayConfiguration opens the connector and LlmProxySecurityConfig matches its chains on that port; both are gated on hephaestus.runtime.worker.enabled",
	},
	{
		path: "hephaestus.sandbox.gateway.max-request-bytes",
		role: "worker",
		why: "SandboxGatewayPayloadSizeFilter enforces it on the gateway chain, which LlmProxySecurityConfig installs gated on hephaestus.runtime.worker.enabled",
	},
	{
		path: "hephaestus.sandbox.gateway.requests-per-minute",
		role: "worker",
		why: "SandboxGatewayRateLimitFilter is installed by LlmProxySecurityConfig, which is gated on hephaestus.runtime.worker.enabled",
	},
	{
		path: "hephaestus.webhook.stream",
		role: "webhook",
		why: "WebhookJetStreamBootstrap and WebhookStreamMonitor are contributed by WebhookProducerBeans, which WebhookConfiguration gates on hephaestus.runtime.webhook.enabled",
	},
	{
		path: "hephaestus.webhook.publish",
		role: "webhook",
		why: "the publish retry policy and JetStreamPublisher are webhook-role beans",
	},
	{
		path: "hephaestus.webhook.shutdown",
		role: "webhook",
		why: "WebhookGracefulShutdown is a webhook-role bean",
	},
	{
		path: "hephaestus.webhook.http",
		role: "webhook",
		why: "WebhookPayloadSizeFilter is registered by WebhookHttpConfiguration, which is @ConditionalOnWebhookRole",
	},
	{
		path: "hephaestus.integration.consumer",
		role: "server",
		why: "IntegrationNatsConsumer and IntegrationConsumerHealthIndicator are gated on hephaestus.runtime.server.enabled",
	},
];

/** Every role has a flag, so a new role cannot be scoped without saying which variable turns it off. */
const ROLE_FLAGS: Record<Role, string> = {
	server: "HEPHAESTUS_RUNTIME_SERVER_ENABLED",
	worker: "HEPHAESTUS_RUNTIME_WORKER_ENABLED",
	webhook: "HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED",
};

/**
 * The lock variable naming the one image every role boots from. A service running it is an
 * application container: whichever slice of the context its roles leave out, the ungated beans — and
 * therefore every setting not listed in `ROLE_SCOPES` — are there and read on it.
 */
const APPLICATION_IMAGE = "HEPHAESTUS_IMAGE_APPLICATION_SERVER";

/**
 * Keys an application container is meant to spell differently from its siblings, because they are
 * what makes it that slice of the deployment rather than another. Everything else describes the
 * deployment, which is one thing: the roles differ, the stack they run in does not.
 */
const PER_CONTAINER = new Map<string, string>([
	["SPRING_PROFILES_ACTIVE", "the profile list is how a container selects the slice it boots"],
	[ROLE_FLAGS.server, "the role flags are the split itself"],
	[ROLE_FLAGS.worker, "the role flags are the split itself"],
	[ROLE_FLAGS.webhook, "the role flags are the split itself"],
	["SPRING_LIQUIBASE_ENABLED", "one container owns the migration and the rest must not race it"],
	["THC_PATH", "the receiver reports NATS through readiness; the others answer liveness"],
]);

/**
 * Declared rather than derived, and the reason is worth stating because it looks like a weakness.
 * Nothing in Compose separates a setting the receiver must have from one it is right not to have:
 * `GH_APP_PRIVATE_KEY` also reaches two of the three containers, and "deliver it to the third too"
 * would be a private key shipped to a container with no use for it. Only the role gate on the bean
 * that reads it decides, so each entry names that bean and a reader can check it against the Java.
 */
interface DeploymentWideSetting {
	readonly variable: string;
	/** The `application.yml` path it binds. A path the file no longer has fails, as in `ROLE_SCOPES`. */
	readonly path: string;
	/** Named beans, so a reader can check the claim rather than take it. Quoted back in every failure. */
	readonly why: string;
}

const DEPLOYMENT_WIDE: readonly DeploymentWideSetting[] = [
	{
		variable: "HEPHAESTUS_AGENT_IMAGE_REFERENCE",
		path: "hephaestus.agent.image.reference",
		why:
			"AgentImageReferenceGuard and AgentImagePinGuard are plain @Components that ADR 0031 keeps " +
			"ungated on purpose, so no pod can boot on an agent image the workers cannot run; a container " +
			"left without the reference derives one from its own version, which is a tag, and refuses to start",
	},
];

/** `application-<profile>.yml` turns roles off too, so a service's profiles decide as much as its env. */
const PROFILE_YML = (profile: string): string =>
	`server/application/src/main/resources/application-${profile}.yml`;
const PROFILES = ["prod", "worker", "webhook", "local", "e2e"];

/**
 * Profile name to the roles that profile's overlay switches off. The names are whatever the overlays
 * spell under `hephaestus.runtime`, not `Role`, so an overlay naming something no longer a role reads
 * back as the dead entry it is instead of being quietly rewritten into a live one.
 */
export type ProfileRoles = ReadonlyMap<string, ReadonlySet<string>>;

/**
 * Which roles each shipped profile overlay switches off. The gate is weaker without it — a webhook
 * variable on a container that disables the role through a profile rather than through its own
 * environment passes — so the shipped-topology test has to build it the same way the CLI does.
 */
export async function readProfileRoles(root = REPO_ROOT): Promise<ProfileRoles> {
	const profileRoles = new Map<string, ReadonlySet<string>>();
	for (const profile of PROFILES) {
		try {
			profileRoles.set(
				profile,
				readDisabledRoles(await readFile(join(root, PROFILE_YML(profile)), "utf8")),
			);
		} catch (error) {
			if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) throw error;
		}
	}
	return profileRoles;
}

/** `${VAR:default}` — Spring's syntax. Stops at `$` so a nested placeholder is skipped, not misread. */
const SPRING_PLACEHOLDER = /\$\{([A-Z0-9_]+):[^}$]*\}/;

function yamlDocuments(text: string): unknown[] {
	return parseAllDocuments(text, { merge: true }).map((document) => {
		const error = document.errors[0];
		if (error) throw error;
		return document.toJS() as unknown;
	});
}

const unquote = (raw: string): string => raw.trim().replace(/^["']|["']$/g, "");

/**
 * `${VAR:-d}` and `${VAR-d}` fall back to `d`; a bare `${VAR}` resolves to the empty string, exactly
 * as Compose leaves it. The `-` is the only optional part of the syntax.
 */
export function composeDefault(raw: string): string {
	const value = unquote(raw);
	const placeholder = /^\$\{[A-Z0-9_]+(?<dash>:?-)?(?<fallback>[^}]*)\}$/.exec(value);
	if (!placeholder) return value;
	const groups = placeholder.groups;
	return groups?.dash === undefined ? "" : (groups.fallback ?? "");
}

interface ApplicationConfig {
	readonly paths: ReadonlySet<string>;
	/** Variable name to the path it binds. */
	readonly placeholders: ReadonlyMap<string, string>;
}

/** Every key path in `application.yml`, and the `${VAR}` placeholders the paths carry. */
export function readApplicationConfig(text: string): ApplicationConfig {
	const paths = new Set<string>();
	const placeholders = new Map<string, string>();
	const visit = (value: unknown, parent: readonly string[]): void => {
		if (!isRecord(value)) return;
		for (const [key, child] of Object.entries(value)) {
			const path = [...parent, key];
			const dotted = path.join(".");
			paths.add(dotted);
			if (typeof child === "string") {
				const variable = SPRING_PLACEHOLDER.exec(child)?.[1];
				if (variable) placeholders.set(variable, dotted);
			}
			visit(child, path);
		}
	};
	for (const document of yamlDocuments(text)) {
		visit(document, []);
	}
	return { paths, placeholders };
}

/**
 * `flags` is what an operator who sets nothing gets, and two services can agree on that and still be
 * handed different values, because `${VAR:?required}` and a valueless key both resolve to nothing.
 * `raw` is what the file writes, so those two stay distinguishable.
 */
export interface ComposeService {
	readonly name: string;
	readonly env: Set<string>;
	readonly flags: Map<string, string>;
	readonly raw: Map<string, string>;
	image: string;
}

/**
 * Compose accepts `environment:` as a mapping or as a `KEY=value` sequence, and both forms deliver
 * the same environment; reading only the mapping would let the sequence form empty a service's
 * environment and pass. A sequence entry with no `=` inherits from the caller's shell, which this
 * gate deliberately does not read, so it delivers nothing here.
 */
function* environmentEntries(environment: unknown): Generator<readonly [string, string]> {
	if (isRecord(environment)) {
		for (const [key, value] of Object.entries(environment)) {
			if (value === null) yield [key, ""];
			else if (typeof value === "string" || typeof value === "number" || typeof value === "boolean")
				yield [key, `${value}`];
		}
		return;
	}
	if (!Array.isArray(environment)) return;
	for (const entry of environment) {
		if (typeof entry !== "string") continue;
		const separator = entry.indexOf("=");
		yield separator === -1 ? [entry, ""] : [entry.slice(0, separator), entry.slice(separator + 1)];
	}
}

/**
 * Services in one Compose file, with `<<:` merges resolved against the file's own `x-*` anchors, so
 * a shared block counts on every service that merges it.
 */
export function readComposeServices(text: string): Map<string, ComposeService> {
	const services = new Map<string, ComposeService>();
	const compose = parse(text, { merge: true }) as unknown;
	if (!isRecord(compose) || !isRecord(compose.services)) return services;

	for (const [name, value] of Object.entries(compose.services)) {
		if (!isRecord(value)) continue;
		const service: ComposeService = {
			name,
			env: new Set(),
			flags: new Map(),
			raw: new Map(),
			image: typeof value.image === "string" ? value.image : "",
		};
		services.set(name, service);
		for (const [key, raw] of environmentEntries(value.environment)) {
			service.env.add(key);
			service.flags.set(key, composeDefault(raw));
			service.raw.set(key, raw);
		}
	}
	return services;
}

/**
 * A role is on unless something turns it off: the service's own environment, or a profile overlay it
 * activates. The application defaults every role on, so silence means yes.
 */
const runsRole = (service: ComposeService, role: Role, profileRoles: ProfileRoles): boolean => {
	if (service.flags.get(ROLE_FLAGS[role]) === "false") return false;
	const profiles = (service.flags.get("SPRING_PROFILES_ACTIVE") ?? "")
		.split(",")
		.map((p) => p.trim());
	return !profiles.some((profile) => profileRoles.get(profile)?.has(role));
};

/** Roles an `application-<profile>.yml` overlay switches off. */
export function readDisabledRoles(text: string): Set<string> {
	const disabled = new Set<string>();
	for (const document of yamlDocuments(text)) {
		if (!isRecord(document) || !isRecord(document.hephaestus)) continue;
		const runtime = document.hephaestus.runtime;
		if (!isRecord(runtime)) continue;
		for (const [role, configuration] of Object.entries(runtime)) {
			if (isRecord(configuration) && configuration.enabled === false) disabled.add(role);
		}
	}
	return disabled;
}

export type ComposeFile = readonly [label: string, text: string];

/** A service that sets a role-scoped variable, named the way a failure has to name it. */
interface Delivery {
	readonly id: string;
	readonly service: ComposeService;
}

/**
 * Every service that sets one variable. The scope hangs off the variable rather than off each
 * delivery because it is a property of the setting, not of who happened to forward it first.
 */
interface DeliveredVariable {
	readonly scope: RoleScope;
	readonly deliveries: Delivery[];
}

interface Analysis {
	readonly failures: string[];
	readonly delivered: ReadonlyMap<string, DeliveredVariable>;
	/** Ids of the services running the application image, so a caller can check the set was found. */
	readonly applicationContainers: readonly string[];
}

export function analyse(
	applicationText: string,
	compose: readonly ComposeFile[],
	profileRoles: ProfileRoles = new Map(),
): Analysis {
	const { paths, placeholders } = readApplicationConfig(applicationText);

	// Longest path first so a nested scope wins over its parent.
	const scopeOrder = [...ROLE_SCOPES].toSorted((a, b) => b.path.length - a.path.length);
	const ownership = new Map<string, RoleScope>();
	for (const [variable, path] of placeholders) {
		const scope = scopeOrder.find((s) => path === s.path || path.startsWith(`${s.path}.`));
		if (scope) ownership.set(variable, scope);
	}

	const failures: string[] = [];
	for (const scope of ROLE_SCOPES) {
		if (!paths.has(scope.path)) {
			failures.push(
				`ROLE_SCOPES declares "${scope.path}" (${scope.role} role), which ${APPLICATION_YML} does not have.\n` +
					"  Point it at wherever the setting moved, or drop the entry — as written it checks nothing.",
			);
		}
	}

	const delivered = new Map<string, DeliveredVariable>();
	const applicationContainers: Delivery[] = [];
	for (const [label, text] of compose) {
		let services: Map<string, ComposeService>;
		try {
			services = readComposeServices(text);
		} catch (error) {
			// Only here is the file's name known, so a YAML error is reported the way every other
			// failure in this list is rather than ending the run as an unlabelled stack trace.
			failures.push(
				`${label} is not valid YAML: ${error instanceof Error ? error.message : String(error)}`,
			);
			continue;
		}
		if (services.size === 0) {
			failures.push(
				`${label} parsed to zero services, so every check below ran against nothing.\n` +
					"  Either the file is not a Compose file or its shape has moved past what this script reads.",
			);
		}
		for (const [name, service] of services) {
			const id = `${label}:${name}`;
			if (service.image.includes(APPLICATION_IMAGE)) applicationContainers.push({ id, service });
			for (const variable of service.env) {
				const scope = ownership.get(variable);
				if (!scope) continue;
				const record = delivered.get(variable) ?? { scope, deliveries: [] };
				delivered.set(variable, record);
				record.deliveries.push({ id, service });
				if (!runsRole(service, scope.role, profileRoles)) {
					failures.push(
						`${id} sets ${variable}, and disables the ${scope.role} role that reads it.\n` +
							`  ${variable} binds ${scope.path} — ${scope.why}.\n` +
							"  On this container those beans do not exist, so the variable configures nothing.\n" +
							`  Move it to a service that runs the ${scope.role} role.`,
					);
				}
			}
		}
	}

	for (const [variable, { scope, deliveries }] of delivered) {
		if (deliveries.some(({ service }) => runsRole(service, scope.role, profileRoles))) continue;
		failures.push(
			`${variable} is forwarded by ${deliveries.map((d) => d.id).join(", ")}, but no service running the ${scope.role} role receives it.\n` +
				`  ${variable} binds ${scope.path} — ${scope.why}.\n` +
				"  Nothing in the deployment can read it, so it and anything documented around it are inert.",
		);
	}

	for (const [variable, scope] of ownership) {
		if (delivered.has(variable)) continue;
		failures.push(
			`${variable} is offered by ${APPLICATION_YML} but no service in the deployment forwards it.\n` +
				`  ${variable} binds ${scope.path} — ${scope.why}.\n` +
				"  The placeholder is what makes it an operator knob, so setting it in .env reaches nothing.\n" +
				`  Forward it from a service that runs the ${scope.role} role, or drop the placeholder.`,
		);
	}

	// disagreed. Compared on the raw spelling rather than the resolved default, because two
	// containers can both resolve to nothing and still be handed different values.
	const spellings = new Map<string, Map<string, string[]>>();
	for (const { id, service } of applicationContainers) {
		for (const [variable, value] of service.raw) {
			if (PER_CONTAINER.has(variable)) continue;
			const byValue = spellings.get(variable) ?? new Map<string, string[]>();
			spellings.set(variable, byValue);
			byValue.set(value, [...(byValue.get(value) ?? []), id]);
		}
	}
	for (const [variable, byValue] of spellings) {
		if (byValue.size < 2) continue;
		const written = [...byValue]
			.map(([value, ids]) => `    ${value === "" ? "<nothing>" : value}\n      ${ids.join(", ")}`)
			.join("\n");
		failures.push(
			`Containers running the application image disagree on ${variable}:\n${written}\n` +
				"  No runtime role gates this setting, so every one of them reads it and they cannot both\n" +
				"  be describing the same deployment.\n" +
				`  Give them one value, or name ${variable} in PER_CONTAINER with the reason it differs.`,
		);
	}

	// omitted. The loop above sees only containers that mention the key, so an absence has to be
	// checked separately: the container that omits it reads the application default instead.
	for (const { variable, path, why } of DEPLOYMENT_WIDE) {
		if (!paths.has(path)) {
			failures.push(
				`DEPLOYMENT_WIDE declares "${path}" (${variable}), which ${APPLICATION_YML} does not have.\n` +
					"  Point it at wherever the setting moved, or drop the entry — as written it checks nothing.",
			);
			continue;
		}
		const missing = applicationContainers.filter(({ service }) => !service.env.has(variable));
		if (missing.length === 0) continue;
		const listed = missing.map(({ id }) => `    ${id}`).join("\n");
		failures.push(
			missing.length === applicationContainers.length
				? `${variable} binds ${path}, and no container running the application image is given it.\n` +
						`  ${why}.\n` +
						"  Every one of them reads it, so the deployment has nowhere to get the value from."
				: `${variable} binds ${path}, and these containers run the application image without it:\n${listed}\n` +
						`  ${why}.\n` +
						"  The setting is not gated on a runtime role, so leaving it off one container does not\n" +
						"  scope it — that container falls back to the application default and reads a different\n" +
						"  deployment than its siblings.",
		);
	}

	return { failures, delivered, applicationContainers: applicationContainers.map((c) => c.id) };
}

if (process.argv[1] === import.meta.filename) {
	const compose: ComposeFile[] = [];
	for (const file of COMPOSE_FILES) {
		compose.push([file, await readFile(join(REPO_ROOT, file), "utf8")]);
	}
	const profileRoles = await readProfileRoles();
	const { failures, delivered, applicationContainers } = analyse(
		await readFile(join(REPO_ROOT, APPLICATION_YML), "utf8"),
		compose,
		profileRoles,
	);
	if (failures.length > 0) {
		for (const failure of failures) console.error(`${failure}\n`);
		process.exit(1);
	}
	console.log(
		`check-env-roles: ${delivered.size} role-scoped variable(s) reach a container that runs their role, ` +
			`and ${applicationContainers.length} application container(s) agree on every setting that is not role-scoped.`,
	);
}
