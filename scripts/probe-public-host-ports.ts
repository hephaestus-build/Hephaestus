import { readFileSync } from "node:fs";
import { isIP } from "node:net";

import { output } from "./lib/process.ts";

const publicPorts = new Set(["80/tcp", "443/tcp", "443/udp"]);
const respondingStates = new Set(["open", "closed", "unfiltered"]);

interface Target {
	addresses: [string, string];
	expectedOnline: boolean;
}

export function probeTargets(config: unknown): Target[] {
	if (!Array.isArray(config) || config.length === 0) {
		throw new Error("List the public IPv4 and IPv6 address of each host.");
	}
	const seen = new Set<string>();
	return config.map((host: unknown) => {
		if (
			typeof host !== "object" ||
			host === null ||
			Object.keys(host).some((key) => !["ipv4", "ipv6", "expectedOnline"].includes(key)) ||
			!("ipv4" in host) ||
			!("ipv6" in host) ||
			typeof host.ipv4 !== "string" ||
			typeof host.ipv6 !== "string" ||
			isIP(host.ipv4) !== 4 ||
			isIP(host.ipv6) !== 6 ||
			host.ipv6.includes("%") ||
			host.ipv6.toLowerCase().startsWith("::ffff:")
		) {
			throw new Error("Use one IPv4 and one IPv6 address per host.");
		}
		const ipv6 = new URL(`http://[${host.ipv6}]/`).hostname.slice(1, -1);
		if (seen.has(host.ipv4) || seen.has(ipv6)) {
			throw new Error("List each public address once.");
		}
		seen.add(host.ipv4);
		seen.add(ipv6);
		const expectedOnline = "expectedOnline" in host ? host.expectedOnline : true;
		if (typeof expectedOnline !== "boolean") {
			throw new Error("Use a boolean for expectedOnline.");
		}
		return { addresses: [host.ipv4, ipv6], expectedOnline };
	});
}

export function assessScan(report: string, expectedOnline: boolean) {
	// A host timeout can leave Nmap's exit code at zero. Require both full scan ranges,
	// one completed host, and an accounting of every TCP and UDP port before accepting it.
	const ranges = Array.from(
		report.matchAll(
			/<scaninfo type="[^"]+" protocol="(?<protocol>tcp|udp)" numservices="65535" services="1-65535"\s*\/>/gu,
		),
		(match) => match.groups?.protocol ?? "",
	).toSorted((left, right) => left.localeCompare(right));
	const ports = Array.from(
		report.matchAll(
			/<port protocol="(?<protocol>tcp|udp)" portid="(?<port>\d+)">\s*<state state="(?<state>[^"]+)"/gu,
		),
		({ groups }) => ({
			port: `${groups?.port ?? ""}/${groups?.protocol ?? ""}`,
			state: groups?.state ?? "",
		}),
	);
	const elided = Array.from(
		report.matchAll(/<extraports state="(?<state>[^"]+)" count="(?<count>\d+)"/gu),
		({ groups }) => ({ state: groups?.state ?? "", count: Number(groups?.count) }),
	);
	const total = ports.length + elided.reduce((sum, group) => sum + group.count, 0);
	if (
		ranges.join(" ") !== "tcp udp" ||
		!/<finished [^>]*exit="success"/u.test(report) ||
		!/<hosts up="1" down="0" total="1"\s*\/>/u.test(report) ||
		/<host\b[^>]*timedout="true"/u.test(report) ||
		total !== 2 * 65_535
	) {
		throw new Error("The probe did not produce a complete TCP and UDP result.");
	}
	const responding = ports.filter(({ state }) => respondingStates.has(state));
	const hiddenReplies = elided.some(({ state }) => respondingStates.has(state));
	const reachable = responding.length > 0 || hiddenReplies;
	if (!reachable && expectedOnline) {
		throw new Error("The address did not answer on either protocol.");
	}
	return {
		reachable,
		hiddenReplies,
		unexpected: responding.filter(({ port }) => !publicPorts.has(port)).map(({ port }) => port),
	};
}

async function probe(address: string, expectedOnline: boolean) {
	const ipv6 = isIP(address) === 6;
	const stdout = await output(
		"/usr/local/libexec/hephaestus-probe-nmap",
		[
			...(ipv6 ? ["-6"] : []),
			"-n",
			"-Pn",
			"-sS",
			"-sU",
			"-p",
			"1-65535",
			// The check needs the ports that answer. UDP silence can mean open or filtered.
			"--defeat-icmp-ratelimit",
			"--min-rate",
			"1200",
			"--max-rate",
			"1200",
			"--max-retries",
			"1",
			"--host-timeout",
			"5m",
			"-oX",
			"-",
			address,
		],
		{ env: { NMAP_PRIVILEGED: "1" }, signal: AbortSignal.timeout(330_000) },
	);
	return assessScan(stdout, expectedOnline);
}

async function main() {
	const config = process.argv[2];
	if (config === undefined || process.argv.length !== 3) {
		throw new Error("Usage: node scripts/probe-public-host-ports.ts CONFIG.json");
	}
	const input: unknown = JSON.parse(readFileSync(config, "utf8"));
	const hosts = probeTargets(input);
	// Check every address even when one probe fails. Limit concurrent scans to one per host.
	const outcomes = await Promise.all(
		hosts.map(async ({ addresses, expectedOnline }, index) => {
			let passed = true;
			for (const address of addresses) {
				const family = isIP(address);
				try {
					const result = await probe(address, expectedOnline);
					if (result.hiddenReplies || result.unexpected.length > 0) {
						console.error(
							`Host ${index + 1}, IPv${family}, unexpected responding ports: ${result.hiddenReplies ? "grouped replies" : result.unexpected.join(", ")}`,
						);
						passed = false;
					} else if (!result.reachable) {
						console.log(`Host ${index + 1}, IPv${family}: unreachable (expected while down).`);
					}
				} catch {
					console.error(`Host ${index + 1}, IPv${family}, no complete probe result.`);
					passed = false;
				}
			}
			return passed;
		}),
	);
	if (outcomes.some((passed) => !passed)) {
		throw new Error(
			"Public port verification failed. Run the check on the probe host for details.",
		);
	}
	console.log(`Public port verification passed for ${hosts.length} hosts on IPv4 and IPv6.`);
}

if (typeof import.meta.main !== "boolean") {
	throw new Error("Use Node.js 24.2 or later.");
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		// Only stdout reaches the workflow log. Keep host and port details on the probe.
		console.log("Public port verification failed. Run the check on the probe host for details.");
		if (error instanceof SyntaxError) {
			console.error("Invalid probe configuration JSON.");
		} else {
			console.error(error instanceof Error ? error.message : "Probe failed.");
		}
		process.exitCode = 1;
	}
}
