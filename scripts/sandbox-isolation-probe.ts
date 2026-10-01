/**
 * What a sandbox can reach on a running installation: the isolation probe ADR 0041 makes a release
 * gate. Run it from the Compose project directory with the env files the stack was started with:
 *
 *     node ../../scripts/sandbox-isolation-probe.ts --env-file .env --env-file release-lock.env
 *
 * It first waits for the worker to report, on its own metrics, that it inspected its agent image
 * through the Docker socket and found the runtime contract it stages for: a worker that cannot reach
 * the daemon, or runs on an image it cannot use, has no isolation worth measuring.
 *
 * Then it attaches `application-worker` to an internal bridge with an isolated gateway address — the
 * network a sandbox without internet access runs on — and starts a container there. Only the worker's
 * sandbox gateway may answer it; the worker's own listeners, the application server, PostgreSQL, NATS
 * and the internet must not. The gateway answering is the control, so a probe that can connect
 * nowhere cannot pass for one that is isolated. That the worker builds exactly this network, and joins
 * it as itself and nothing else, is held by `DockerClientOperationsTest` and `SandboxNetworkManagerTest`;
 * this probe proves what only the running installation can: which listeners answer on that network
 * and that no other service is reachable from it. A sandbox an administrator allows onto the internet
 * runs on an ordinary bridge, which is outside this proof.
 *
 * The probe container runs the stack's own volume initialiser image, which the release lock pins,
 * so nothing is pulled that the installation does not already run.
 */
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

interface Target {
	readonly name: string;
	readonly host: string;
	readonly port: string;
	readonly reachable: boolean;
}

function docker(...args: string[]): string {
	const result = spawnSync("docker", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	if (result.status !== 0) {
		throw new Error(`docker ${args.join(" ")} failed:\n${result.stdout}${result.stderr}`);
	}
	return result.stdout.trim();
}

/**
 * The worker's sandbox gateway port, which the shipped stack sets through `SANDBOX_API_PORT`. Docker
 * picks that one variable out of the running container, so the rest of its environment, which holds
 * secrets, never reaches this process or an error message.
 */
function workerGatewayPort(id: string): string {
	return tcpPort(
		docker(
			"inspect",
			"--format",
			'{{range .Config.Env}}{{$pair := split . "="}}{{if eq (index $pair 0) "SANDBOX_API_PORT"}}{{index $pair 1}}{{end}}{{end}}',
			id,
		),
	);
}

export function tcpPort(value: string): string {
	if (!/^[1-9]\d{0,4}$/u.test(value) || Number(value) > 65_535) {
		throw new Error(
			`application-worker's SANDBOX_API_PORT is ${JSON.stringify(value)}, not a TCP port`,
		);
	}
	return value;
}

const composeArguments = process.argv.slice(2);

function container(service: string): string {
	const id = docker("compose", ...composeArguments, "ps", "--all", "--quiet", service);
	if (id === "") {
		throw new Error(`${service} is not part of the running stack`);
	}
	return id;
}

/** The container's address on `network`, or every address it has when `network` is omitted. */
function addresses(id: string, network?: string): string[] {
	const template =
		network === undefined
			? "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}"
			: `{{(index .NetworkSettings.Networks "${network}").IPAddress}}`;
	return docker("inspect", "--format", template, id).split(/\s+/u).filter(Boolean);
}

/** The first line of `nc` output per target, `open` or `closed`, read back from the probe container. */
function probe(network: string, image: string, targets: readonly Target[]): Map<Target, boolean> {
	const script = targets
		.map(
			({ host, port }) =>
				`if nc -z -w 3 ${host} ${port} >/dev/null 2>&1; then echo open; else echo closed; fi`,
		)
		.join("\n");
	const lines = docker(
		"run",
		"--rm",
		"--network",
		network,
		"--entrypoint",
		"sh",
		image,
		"-c",
		script,
	).split("\n");
	if (lines.length !== targets.length) {
		throw new Error(`the probe answered ${lines.length} lines for ${targets.length} targets`);
	}
	return new Map(targets.map((target, index) => [target, lines[index] === "open"]));
}

/**
 * The outcomes of the worker's agent image check, from its management port on the network it shares
 * with the stack (`AgentImageContractVerifier` counts one per check under `agent.image.contract`).
 */
function contractOutcomes(stackNetwork: string, workerAddress: string, image: string): string[] {
	const metrics = docker(
		"run",
		"--rm",
		"--network",
		stackNetwork,
		"--entrypoint",
		"sh",
		image,
		"-c",
		`wget -q -T 5 -O - http://${workerAddress}:9090/actuator/prometheus || true`,
	);
	return [
		...metrics.matchAll(
			/^agent_image_contract_total\{[^}]*outcome="(?<outcome>\w+)"[^}]*\} (?<count>\S+)$/gmu,
		),
	]
		.filter((match) => Number(match.groups?.count) > 0)
		.map((match) => match.groups?.outcome ?? "");
}

async function waitForVerifiedAgentImage(
	stackNetwork: string,
	workerAddress: string,
	image: string,
) {
	for (let attempt = 1; attempt <= 60; attempt += 1) {
		const outcomes = contractOutcomes(stackNetwork, workerAddress, image);
		if (outcomes.includes("verified")) {
			console.log("application-worker inspected its agent image and verified its runtime contract");
			return;
		}
		if (outcomes.length > 0) {
			throw new Error(
				`application-worker checked its agent image with outcome ${outcomes.join(", ")}`,
			);
		}
		await sleep(5000);
	}
	throw new Error("application-worker reported no agent image check within five minutes");
}

if (import.meta.main) {
	const worker = container("application-worker");
	const gatewayPort = workerGatewayPort(worker);
	const image = docker("inspect", "--format", "{{.Config.Image}}", container("volume-init"));
	const stackNetwork = docker(
		"inspect",
		"--format",
		"{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}",
		worker,
	)
		.split(/\s+/u)
		.find(Boolean);
	const [workerAddress] = stackNetwork === undefined ? [] : addresses(worker, stackNetwork);
	if (stackNetwork === undefined || workerAddress === undefined) {
		throw new Error("application-worker is on no network");
	}
	await waitForVerifiedAgentImage(stackNetwork, workerAddress, image);
	const network = `hephaestus-sandbox-probe--${randomUUID()}`;

	docker(
		"network",
		"create",
		"--driver",
		"bridge",
		"--internal",
		"--opt",
		"com.docker.network.bridge.gateway_mode_ipv4=isolated",
		network,
	);
	let failures: string[] = [];
	try {
		docker("network", "connect", network, worker);
		const [gateway] = addresses(worker, network);
		if (gateway === undefined) {
			throw new Error(`application-worker has no address on ${network}`);
		}
		const elsewhere = (name: string, service: string, port: string): Target[] => [
			{ name: `${name} by name`, host: service, port, reachable: false },
			...addresses(container(service)).map((host) => ({
				name: `${name} at ${host}`,
				host,
				port,
				reachable: false,
			})),
		];
		const targets: Target[] = [
			{ name: "the worker's sandbox gateway", host: gateway, port: gatewayPort, reachable: true },
			{ name: "the worker's application listener", host: gateway, port: "8080", reachable: false },
			{ name: "the worker's management listener", host: gateway, port: "9090", reachable: false },
			{
				name: `the worker's management listener at ${workerAddress}`,
				host: workerAddress,
				port: "9090",
				reachable: false,
			},
			{
				name: `the worker's gateway at ${workerAddress}`,
				host: workerAddress,
				port: gatewayPort,
				reachable: false,
			},
			...elsewhere("application-server", "application-server", "8080"),
			...elsewhere("PostgreSQL", "postgres", "5432"),
			...elsewhere("NATS", "nats-server", "4222"),
			{ name: "the internet by address", host: "1.1.1.1", port: "443", reachable: false },
			{ name: "the internet by name", host: "example.com", port: "443", reachable: false },
		];
		const answers = probe(network, image, targets);
		for (const [target, open] of answers) {
			console.log(
				`${open ? "answers" : "refused"}  ${target.name} (${target.host}:${target.port})`,
			);
		}
		failures = [...answers]
			.filter(([target, open]) => open !== target.reachable)
			.map(([target, open]) =>
				open
					? `A sandbox reached ${target.name}; only the worker's gateway may answer it.`
					: `A sandbox could not reach ${target.name}, so this probe proved nothing about isolation.`,
			);
	} finally {
		spawnSync("docker", ["network", "disconnect", "--force", network, worker]);
		spawnSync("docker", ["network", "rm", network]);
	}

	if (failures.length > 0) {
		for (const failure of failures) {
			console.error(`::error::${failure}`);
		}
		process.exit(1);
	}
	console.log("sandbox-isolation-probe: a sandbox reaches the worker's gateway and nothing else.");
}
