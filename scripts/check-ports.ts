import { connect } from "node:net";
import { join } from "node:path";

import { positivePort, readEnvFile } from "./lib/env.ts";

export interface ServicePort {
	name: string;
	port: number;
}

export function duplicatePorts(services: ServicePort[]): Array<[ServicePort, ServicePort]> {
	const seen = new Map<number, ServicePort>();
	const duplicates: Array<[ServicePort, ServicePort]> = [];
	for (const service of services) {
		const previous = seen.get(service.port);
		if (previous) duplicates.push([service, previous]);
		else seen.set(service.port, service);
	}
	return duplicates;
}

export function isListening(port: number, host = "127.0.0.1"): Promise<boolean> {
	return new Promise((resolve) => {
		const socket = connect({ host, port });
		socket.setTimeout(500);
		socket.once("connect", () => socket.destroy());
		socket.once("close", (hadError) => resolve(!hadError));
		socket.once("error", () => resolve(false));
		socket.once("timeout", () => socket.destroy());
	});
}

async function isPortListening(port: number): Promise<boolean> {
	return (await Promise.all([isListening(port, "127.0.0.1"), isListening(port, "::1")])).some(
		Boolean,
	);
}

async function main(): Promise<void> {
	const args = process.argv.slice(2);
	if (args.some((arg) => !["--quiet", "-q"].includes(arg))) {
		console.error("Usage: node scripts/check-ports.ts [--quiet]");
		process.exitCode = 2;
		return;
	}
	const quiet = args.length > 0;
	const fileEnv = await readEnvFile(join(import.meta.dirname, "../server/.env"));
	const value = (name: string, fallback: string): string =>
		process.env[name] ?? fileEnv[name] ?? fallback;
	let services: ServicePort[];
	try {
		services = [
			{ name: "PostgreSQL", port: positivePort(value("POSTGRES_PORT", "5432"), "POSTGRES_PORT") },
			{
				name: "Application server",
				port: positivePort(value("SERVER_PORT", "8080"), "SERVER_PORT"),
			},
			{
				name: "Sandbox gateway",
				port: positivePort(value("SANDBOX_API_PORT", "8081"), "SANDBOX_API_PORT"),
			},
			{
				name: "Mailpit SMTP",
				port: positivePort(value("MAILPIT_SMTP_PORT", "1025"), "MAILPIT_SMTP_PORT"),
			},
			{
				name: "Mailpit UI",
				port: positivePort(value("MAILPIT_UI_PORT", "8025"), "MAILPIT_UI_PORT"),
			},
			{ name: "Webapp (Vite)", port: positivePort(value("WEBAPP_PORT", "4200"), "WEBAPP_PORT") },
		];
	} catch (error) {
		console.error(error instanceof Error ? error.message : String(error));
		process.exitCode = 2;
		return;
	}
	const duplicates = duplicatePorts(services);
	if (!quiet) {
		console.log("\nHephaestus port availability check\n===================================\n");
		for (const [service, previous] of duplicates)
			console.log(`  DUPLICATE :${service.port}  ${service.name} conflicts with ${previous.name}`);
		if (duplicates.length) console.log("\nWarning: Duplicate port assignments detected.\n");
	}
	const results = await Promise.all(
		services.map(async (service) => ({ service, busy: await isPortListening(service.port) })),
	);
	const occupied = results.filter(({ busy }) => busy);
	if (!quiet) {
		for (const { service, busy } of results)
			console.log(`  ${busy ? "OCCUPIED" : "FREE"}  :${service.port}  ${service.name}`);
		console.log(
			occupied.length
				? `\n${occupied.length} port(s) already in use.\n`
				: "\nAll ports are available.\n",
		);
	}
	if (occupied.length || duplicates.length) process.exitCode = 1;
}

if (import.meta.main) await main();
