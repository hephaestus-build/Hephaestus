import { connect } from "node:net";
import path from "node:path";

import { defaultPort, MANAGEMENT_OFFSET, PORTS } from "./dev-env.ts";
import { positivePort, readEnvFile } from "./lib/env.ts";

export interface ServicePort {
	name: string;
	port: number;
}

export function duplicatePorts(services: ServicePort[]): [ServicePort, ServicePort][] {
	const seen = new Map<number, ServicePort>();
	const duplicates: [ServicePort, ServicePort][] = [];
	for (const service of services) {
		const previous = seen.get(service.port);
		if (previous) {
			duplicates.push([service, previous]);
		} else {
			seen.set(service.port, service);
		}
	}
	return duplicates;
}

export async function isListening(port: number, host = "127.0.0.1"): Promise<boolean> {
	const { promise, resolve } = Promise.withResolvers<boolean>();
	const socket = connect({ host, port });
	socket.setTimeout(500);
	socket.once("connect", () => {
		socket.destroy();
	});
	socket.once("close", (hadError) => resolve(!hadError));
	socket.once("error", () => resolve(false));
	socket.once("timeout", () => {
		socket.destroy();
	});
	return promise;
}

async function isPortListening(port: number): Promise<boolean> {
	const listening = await Promise.all([isListening(port, "127.0.0.1"), isListening(port, "::1")]);
	return listening.some(Boolean);
}

async function main(): Promise<void> {
	const args = process.argv.slice(2);
	if (args.some((arg) => !["--quiet", "-q"].includes(arg))) {
		console.error("Usage: node scripts/check-ports.ts [--quiet]");
		process.exitCode = 2;
		return;
	}
	const quiet = args.length > 0;
	const fileEnv = await readEnvFile(path.join(import.meta.dirname, "../server/.env"));
	const value = (name: string, fallback: string): string =>
		process.env[name] ?? fileEnv[name] ?? fallback;
	let services: ServicePort[];
	try {
		const server = positivePort(
			value("SERVER_PORT", String(defaultPort("SERVER_PORT"))),
			"SERVER_PORT",
		);
		services = PORTS.map(({ name, port, label }) => ({
			name: label,
			port: positivePort(
				value(name, String(name === "MANAGEMENT_PORT" ? server + MANAGEMENT_OFFSET : port)),
				name,
			),
		}));
	} catch (error) {
		console.error(error instanceof Error ? error.message : String(error));
		process.exitCode = 2;
		return;
	}
	const duplicates = duplicatePorts(services);
	if (!quiet) {
		console.log("\nHephaestus port availability check\n===================================\n");
		for (const [service, previous] of duplicates) {
			console.log(`  DUPLICATE :${service.port}  ${service.name} conflicts with ${previous.name}`);
		}
		if (duplicates.length > 0) {
			console.log("\nWarning: Duplicate port assignments detected.\n");
		}
	}
	const results = await Promise.all(
		services.map(async (service) => ({ service, busy: await isPortListening(service.port) })),
	);
	const occupied = results.filter(({ busy }) => busy);
	if (!quiet) {
		for (const { service, busy } of results) {
			console.log(`  ${busy ? "OCCUPIED" : "FREE"}  :${service.port}  ${service.name}`);
		}
		console.log(
			occupied.length > 0
				? `\n${occupied.length} port(s) already in use.\n`
				: "\nAll ports are available.\n",
		);
	}
	if (occupied.length > 0 || duplicates.length > 0) {
		process.exitCode = 1;
	}
}

if (import.meta.main) {
	await main();
}
