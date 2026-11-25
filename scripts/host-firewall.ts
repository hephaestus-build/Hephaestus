import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { chmodSync, existsSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { isIP } from "node:net";
import { parseArgs } from "node:util";

type Family = 4 | 6;
interface Rule {
	family?: Family;
	source?: string;
	port?: number;
	protocol?: "tcp" | "udp";
	device?: string;
}

type DriftReason =
	| "netfilter_persistent"
	| "reverse_path_filter"
	| "ipv6_disabled"
	| "boot_disabled"
	| "default_policy"
	| "saved_rules"
	| "live_rules"
	| "check_error";

interface Problem {
	reason: DriftReason;
	message: string;
}

const families = [4, 6] as const;
const defaults = [
	{ chain: "INPUT", direction: "incoming", policy: "DROP" },
	{ chain: "OUTPUT", direction: "outgoing", policy: "ACCEPT" },
	{ chain: "FORWARD", direction: "routed", policy: "DROP" },
] as const;
const inputHooks = [
	"before-logging",
	"before",
	"after",
	"after-logging",
	"reject",
	"track",
] as const;
const logOnlyChains = [
	"before-logging-input",
	"after-logging-input",
	"reject-input",
	"track-input",
	"logging-allow",
	"logging-deny",
] as const;
const packetFilter = { 4: "/usr/sbin/iptables", 6: "/usr/sbin/ip6tables" } as const;
const systemctl = "/usr/bin/systemctl";
const ufwDefaults = "/etc/default/ufw";
const ufwFiles = {
	"user.rules": "/etc/ufw/user.rules",
	"user6.rules": "/etc/ufw/user6.rules",
	"ufw.conf": "/etc/ufw/ufw.conf",
};

function addressFamily(address: string, field: string): Family {
	const family = isIP(address);
	if (
		(family !== 4 && family !== 6) ||
		address.includes("%") ||
		address.toLowerCase().startsWith("::ffff:")
	) {
		throw new Error(`Invalid IP address in ${field}.`);
	}
	return family;
}

function canonicalIpv6(address: string) {
	return new URL(`http://[${address}]/`).hostname.slice(1, -1);
}

function networkAddress(address: string, family: Family, length: number) {
	let words = address.split(".");
	if (family === 6) {
		const [head, tail] = address.split("::");
		const left = head?.split(":").filter(Boolean) ?? [];
		const right = tail?.split(":").filter(Boolean) ?? [];
		const zeroes = Array.from({ length: 8 - left.length - right.length }, () => "0");
		words = [...left, ...zeroes, ...right];
	}
	const width = family === 4 ? 8 : 16;
	const radix = family === 4 ? 10 : 16;
	const masked = words.map((part, index) => {
		const hostBits = Math.min(width, Math.max(0, (index + 1) * width - length));
		const size = 2 ** hostBits;
		return (Math.floor(Number.parseInt(part, radix) / size) * size).toString(radix);
	});
	return family === 4 ? masked.join(".") : canonicalIpv6(masked.join(":"));
}

function sources(values: unknown, field: string, subnet: boolean): Rule[] {
	if (!Array.isArray(values)) {
		throw new Error(`${field} must be an array.`);
	}
	const rules = values.map((value: unknown) => {
		if (typeof value !== "string") {
			throw new Error(`Invalid value in ${field}.`);
		}
		const [address = "", prefix, ...extra] = value.split("/");
		const family = addressFamily(address, field);
		const bits = family === 4 ? 32 : 128;
		if (
			extra.length > 0 ||
			(subnet ? !/^[1-9][0-9]{0,2}$/u.test(prefix ?? "") : prefix !== undefined) ||
			Number(prefix) > bits
		) {
			throw new Error(`Invalid prefix in ${field}.`);
		}
		const canonical = family === 4 ? address : canonicalIpv6(address);
		const length = prefix === undefined ? bits : Number(prefix);
		if (subnet && networkAddress(canonical, family, length) !== canonical) {
			throw new Error(`Use network addresses in ${field}.`);
		}
		return { family, source: `${canonical}/${length}` };
	});
	const unique = new Set(rules.map((rule) => rule.source));
	if (unique.size !== rules.length) {
		throw new Error(`Use distinct entries in ${field}.`);
	}
	if (subnet && rules.length === 0) {
		throw new Error(`Specify at least one network in ${field}.`);
	}
	if (!subnet && new Set(rules.map((rule) => rule.family)).size !== rules.length) {
		throw new Error(`Use at most one address per IP family in ${field}.`);
	}
	return rules;
}

export function rulesFor(config: unknown): Rule[] {
	if (
		typeof config !== "object" ||
		config === null ||
		!("sshVpnCidrs" in config) ||
		!("monitoringAddresses" in config) ||
		!("k3s" in config) ||
		!("http3" in config) ||
		typeof config.k3s !== "boolean" ||
		typeof config.http3 !== "boolean" ||
		Object.keys(config).some(
			(key) =>
				!["sshVpnCidrs", "monitoringAddresses", "k3s", "http3", "publicInterface"].includes(key),
		)
	) {
		throw new Error(
			"Use sshVpnCidrs, monitoringAddresses, k3s, http3 and optional publicInterface in the firewall configuration.",
		);
	}
	const ssh = sources(config.sshVpnCidrs, "sshVpnCidrs", true);
	const monitoring = sources(config.monitoringAddresses, "monitoringAddresses", false);
	return [
		...ssh.map((source) => ({ ...source, port: 22, protocol: "tcp" as const })),
		...monitoring.map((source) => ({ ...source, port: 9100, protocol: "tcp" as const })),
		{ port: 80, protocol: "tcp" },
		{ port: 443, protocol: "tcp" },
		...(config.http3 ? [{ port: 443, protocol: "udp" as const }] : []),
		...(config.k3s ? [{ device: "cni0" }, { device: "flannel.1" }] : []),
	];
}

export function publicInterface(config: unknown, defaultRoute: () => string) {
	const names =
		typeof config === "object" && config !== null && "publicInterface" in config
			? [config.publicInterface]
			: [
					...new Set(
						Array.from(
							defaultRoute().matchAll(/\bdev (?<name>\S+)/gu),
							(match) => match.groups?.name,
						),
					),
				];
	const [name] = names;
	if (
		names.length !== 1 ||
		typeof name !== "string" ||
		!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,14}$/u.test(name) ||
		["all", "default", "lo", "cni0", "flannel.1"].includes(name)
	) {
		throw new Error("Set publicInterface to the public IPv4 interface name.");
	}
	return name;
}

export function reversePathDrift(all: string, device: string): Problem[] {
	if (["0", "1"].includes(all.trim()) && device.trim() === "1") {
		return [];
	}
	return [
		{
			reason: "reverse_path_filter",
			message:
				"Set strict IPv4 reverse-path filtering on the public interface. The global value must be 0 or 1.",
		},
	];
}

function setting(contents: string, name: string): string | undefined {
	const entries = contents.split("\n").filter((line) => line.startsWith(`${name}=`));
	return entries.length === 1
		? entries[0]?.slice(name.length + 1).replace(/^"(?<value>[^"]*)"$/u, "$1")
		: undefined;
}

function run(command: string, args: string[]): string {
	try {
		return execFileSync(command, args, {
			encoding: "utf8",
			env: { ...process.env, LC_ALL: "C" },
			timeout: 15_000,
			maxBuffer: 16 * 1024 * 1024,
			stdio: ["ignore", "pipe", "pipe"],
		}).trim();
	} catch (error) {
		const detail =
			error instanceof Error && "stderr" in error && typeof error.stderr === "string"
				? error.stderr.trim()
				: "";
		throw new Error(
			[`Could not complete ${command} ${args[0] ?? ""}.`, detail].filter(Boolean).join("\n"),
			{ cause: error },
		);
	}
}

function unitState(
	unit: string,
	property: "ActiveState" | "LoadState" | "SubState" | "UnitFileState",
) {
	return run(systemctl, ["show", "--value", `--property=${property}`, unit]);
}

export function savedDrift(contents: string, family: Family, rules: Rule[]) {
	const prefix = family === 4 ? "ufw" : "ufw6";
	const input = `${prefix}-user-input`;
	const hostPrefix = family === 4 ? /\/32$/u : /\/128$/u;
	const wanted = rules
		.filter((rule) => rule.family === undefined || rule.family === family)
		.map((rule) =>
			[
				"-A",
				input,
				...(rule.device === undefined ? [] : ["-i", rule.device]),
				...(rule.protocol === undefined ? [] : ["-p", rule.protocol, "--dport", String(rule.port)]),
				...(rule.source === undefined ? [] : ["-s", rule.source.replace(hostPrefix, "")]),
				"-j",
				"ACCEPT",
			].join(" "),
		)
		.toSorted();
	const actual = contents
		.split("\n")
		.filter((line) => line.startsWith(`-A ${input} `))
		.map((line) =>
			line.replace(
				/ -s (?<source>\S+)/u,
				(_match, source: string) => ` -s ${source.replace(hostPrefix, "")}`,
			),
		)
		.toSorted();
	return (
		actual.join("\n") !== wanted.join("\n") ||
		contents.split("\n").some((line) => /^-A ufw6?-user-(?:forward|output) /u.test(line))
	);
}

function liveRule(rule: Rule, chain: string): string {
	return [
		"-A",
		chain,
		...(rule.source === undefined ? [] : ["-s", rule.source]),
		...(rule.device === undefined ? [] : ["-i", rule.device]),
		...(rule.protocol === undefined
			? []
			: ["-p", rule.protocol, "-m", rule.protocol, "--dport", String(rule.port)]),
		"-j",
		"ACCEPT",
	].join(" ");
}

export function liveDrift(lines: string[], family: Family, rules: Rule[]): string[] {
	const prefix = family === 4 ? "ufw" : "ufw6";
	const input = `${prefix}-user-input`;
	const familyRules = rules.filter((rule) => rule.family === undefined || rule.family === family);
	const wanted = familyRules
		.map((rule) => liveRule(rule, input))
		.toSorted((left, right) => left.localeCompare(right));
	const actual = lines
		.filter((line) => line.startsWith(`-A ${input} `))
		.toSorted((left, right) => left.localeCompare(right));
	const problems: string[] = [];
	for (const { chain, policy } of defaults) {
		const before = `${prefix}-before-${chain.toLowerCase()}`;
		const user = `${prefix}-user-${chain.toLowerCase()}`;
		if (
			!lines.includes(`-P ${chain} ${policy}`) ||
			!lines.includes(`-A ${chain} -j ${before}`) ||
			!lines.includes(`-A ${before} -j ${user}`)
		) {
			problems.push(`IPv${family} UFW ${chain} chain or policy differs.`);
		}
		if (chain !== "INPUT" && lines.some((line) => line.startsWith(`-A ${user} `))) {
			problems.push(`IPv${family} UFW ${chain} has extra rules.`);
		}
	}
	if (actual.join("\n") !== wanted.join("\n")) {
		problems.push(`IPv${family} live UFW input rules differ.`);
	}
	if (
		lines.some((line) => unmanagedInputRule(line, prefix, family)) ||
		notLocalDrift(lines, prefix)
	) {
		problems.push(`IPv${family} has an unmanaged input rule.`);
	}
	if (
		deniesBefore(lines, "INPUT", `${prefix}-before-input`, familyRules) ||
		deniesBefore(lines, `${prefix}-before-input`, input) ||
		(lines
			.slice(0, Math.max(lines.indexOf(`-A INPUT -j ${prefix}-before-input`), 0))
			.includes(`-A INPUT -j ${prefix}-before-logging-input`) &&
			deniesBefore(lines, `${prefix}-before-logging-input`))
	) {
		problems.push(`IPv${family} has an input deny before the required UFW rules.`);
	}
	return problems;
}

function deniesBefore(lines: string[], chain: string, next?: string, rules?: Rule[]) {
	const hook = next === undefined ? lines.length : lines.indexOf(`-A ${chain} -j ${next}`);
	return lines.slice(0, Math.max(hook, 0)).some((line) => {
		const rule = withoutComment(line);
		return (
			rule.startsWith(`-A ${chain} `) &&
			(/ -[jg] (?:DROP|REJECT)(?: |$)/u.test(rule) ||
				(next !== undefined && / -j RETURN(?: |$)/u.test(rule))) &&
			rule !== `-A ${chain} -m conntrack --ctstate INVALID -j DROP` &&
			rule !== `-A ${chain} -m rt --rt-type 0 -j DROP` &&
			(rules === undefined || denyCanBlockInput(rule, rules))
		);
	});
}

function denyCanBlockInput(line: string, rules: Rule[]) {
	const scope =
		/^-A INPUT -i (?<device>[\w.:-]+) -p (?<protocol>tcp|udp) -m (?:tcp|udp|multiport) --dports? (?<ports>\d+(?:,\d+)*) -j (?:DROP|REJECT|RETURN)(?: |$)/u.exec(
			line,
		)?.groups;
	if (scope?.device === undefined || scope.ports === undefined) {
		return true;
	}
	// k3s reaches INPUT from its Pod and overlay interfaces. Keep unrelated ingress denies.
	const ports = new Set(scope.ports.split(",").map(Number));
	return rules.some(
		(rule) =>
			rule.device === scope.device ||
			(rule.port !== undefined && rule.protocol === scope.protocol && ports.has(rule.port)),
	);
}

function withoutComment(line: string) {
	return line.replaceAll(/ -m comment --comment (?:"(?:[^"\\]|\\.)*"|[^"\s]+)/gu, "");
}

function notLocalDrift(lines: string[], prefix: string) {
	const chain = `${prefix}-not-local`;
	const rules = lines.filter((line) => line.startsWith(`-A ${chain} `)).map(withoutComment);
	if (rules.length === 0 && !lines.includes(`-A ${prefix}-before-input -j ${chain}`)) {
		return false;
	}
	// The early returns are what keep the final drop from blocking traffic to this host.
	const stock = [
		...["LOCAL", "MULTICAST", "BROADCAST"].map(
			(type) => `-m addrtype --dst-type ${type} -j RETURN`,
		),
		`-m limit --limit 3/min --limit-burst 10 -j ${prefix}-logging-deny`,
		"-j DROP",
	];
	return rules.join("\n") !== stock.map((rule) => `-A ${chain} ${rule}`).join("\n");
}

function unmanagedInputRule(line: string, prefix: string, family: Family) {
	const [action, chain] = line.split(" ");
	const policyRule = withoutComment(line);
	const target = / -[jg] (?<jump>\S+)/u.exec(policyRule)?.groups?.jump;
	if (action !== "-A" || chain === undefined || target === undefined) {
		return false;
	}
	const match = policyRule.slice(`-A ${chain} `.length);
	if (chain === "INPUT") {
		return unmanagedKernelInputRule(match, target, prefix);
	}
	const hookName = chain.startsWith(`${prefix}-`) ? chain.slice(prefix.length + 1) : "";
	const observabilityOnly = target === "LOG" || target === "RETURN";
	if (logOnlyChains.some((name) => hookName === name)) {
		return (
			!observabilityOnly &&
			!(hookName === "before-logging-input" && ["DROP", "REJECT"].includes(target))
		);
	}
	if (hookName === "before-input") {
		return unmanagedBeforeInputRule(match, target, prefix, family);
	}
	switch (hookName) {
		case "after-input": {
			return !observabilityOnly && target !== `${prefix}-skip-to-policy-input`;
		}
		case "skip-to-policy-input": {
			return match !== "-j DROP";
		}
		case "user-limit": {
			return (
				!observabilityOnly &&
				match !== `-j REJECT --reject-with ${family === 4 ? "icmp" : "icmp6"}-port-unreachable`
			);
		}
		case "user-limit-accept": {
			return match !== "-j ACCEPT";
		}
		default: {
			// Stock user-input and not-local paths are compared separately, in order where needed.
			return false;
		}
	}
}

function unmanagedBeforeInputRule(rule: string, target: string, prefix: string, family: Family) {
	return (
		!["DROP", "REJECT", "RETURN", "LOG"].includes(target) &&
		![
			`-j ${prefix}-not-local`,
			`-j ${prefix}-user-input`,
			`-m conntrack --ctstate INVALID -j ${prefix}-logging-deny`,
		].includes(rule) &&
		!(target === "ACCEPT" && standardBeforeInputAccept(rule, family))
	);
}

function unmanagedKernelInputRule(rule: string, target: string, prefix: string) {
	// kube-router marks traffic already admitted by its NetworkPolicies.
	return (
		rule !== "-m mark --mark 0x20000/0x20000 -j ACCEPT" &&
		rule !== "-i lo -j ACCEPT" &&
		!inputHooks.some((name) => rule === `-j ${prefix}-${name}-input`) &&
		![
			"DROP",
			"REJECT",
			"RETURN",
			"LOG",
			"KUBE-ROUTER-INPUT",
			"KUBE-FIREWALL",
			"KUBE-PROXY-FIREWALL",
			"KUBE-NODEPORTS",
			"KUBE-EXTERNAL-SERVICES",
			"KUBE-SERVICES",
		].includes(target)
	);
}

function standardBeforeInputAccept(rule: string, family: Family) {
	const match = rule.slice(0, -" -j ACCEPT".length);
	if (
		match === "-i lo" ||
		match === "-m conntrack --ctstate RELATED,ESTABLISHED" ||
		(family === 4 ? match.startsWith("-p icmp ") : /^(?:-s fe80::\/10 )?-p ipv6-icmp /u.test(match))
	) {
		return true;
	}
	return (
		family === 4
			? [
					"-p udp -m udp --sport 67 --dport 68",
					"-d 224.0.0.251/32 -p udp -m udp --dport 5353",
					"-d 239.255.255.250/32 -p udp -m udp --dport 1900",
				]
			: [
					"-s fe80::/10 -d fe80::/10 -p udp -m udp --sport 547 --dport 546",
					"-d ff02::fb/128 -p udp -m udp --dport 5353",
					"-d ff02::f/128 -p udp -m udp --dport 1900",
				]
	).includes(match);
}

export function conflictingSnapshot(unit: string, ipv4: boolean, ipv6: boolean) {
	return ["enabled", "enabled-runtime"].includes(unit) && (ipv4 || ipv6);
}

function snapshotDrift() {
	if (
		conflictingSnapshot(
			unitState("netfilter-persistent.service", "UnitFileState"),
			existsSync("/etc/iptables/rules.v4"),
			existsSync("/etc/iptables/rules.v6"),
		)
	) {
		return "netfilter-persistent is enabled with a saved rules snapshot. Retire the snapshot before applying UFW.";
	}
	return null;
}

export function settingsDrift(configuration: string, ufwConfig: string, unit: string): Problem[] {
	const problems: Problem[] = [];
	if (setting(configuration, "IPV6") !== "yes") {
		problems.push({ reason: "ipv6_disabled", message: "UFW IPv6 support is disabled." });
	}
	if (setting(ufwConfig, "ENABLED") !== "yes" || unit !== "enabled") {
		problems.push({ reason: "boot_disabled", message: "UFW does not start at boot." });
	}
	for (const { chain, direction, policy } of defaults) {
		if (setting(configuration, `DEFAULT_${chain}_POLICY`) !== policy) {
			problems.push({
				reason: "default_policy",
				message: `The saved UFW default for ${direction} traffic differs.`,
			});
		}
	}
	return problems;
}

function drift(config: unknown): Problem[] {
	const rules = rulesFor(config);
	const device = publicInterface(config, () =>
		run("/usr/sbin/ip", ["-o", "-4", "route", "show", "default"]),
	);
	const knobs = "/proc/sys/net/ipv4/conf";
	const problems = settingsDrift(
		readFileSync(ufwDefaults, "utf8"),
		readFileSync(ufwFiles["ufw.conf"], "utf8"),
		unitState("ufw.service", "UnitFileState"),
	);
	problems.push(
		...reversePathDrift(
			readFileSync(`${knobs}/all/rp_filter`, "utf8"),
			readFileSync(`${knobs}/${device}/rp_filter`, "utf8"),
		),
	);
	const snapshot = snapshotDrift();
	if (snapshot !== null) {
		problems.push({ reason: "netfilter_persistent", message: snapshot });
	}
	for (const family of families) {
		const file = family === 4 ? ufwFiles["user.rules"] : ufwFiles["user6.rules"];
		if (savedDrift(readFileSync(file, "utf8"), family, rules)) {
			problems.push({ reason: "saved_rules", message: `Saved IPv${family} UFW rules differ.` });
		}
		for (const message of liveDrift(
			run(packetFilter[family], ["-w", "5", "-S"]).split("\n"),
			family,
			rules,
		)) {
			problems.push({ reason: "live_rules", message });
		}
	}
	return problems;
}

export function renderPolicyMetrics(reasons: DriftReason[], seconds: number): string {
	const uniqueReasons = [...new Set(reasons)].toSorted((left, right) => left.localeCompare(right));
	return [
		"# HELP host_firewall_policy_ok Whether the host firewall matches its policy.",
		"# TYPE host_firewall_policy_ok gauge",
		`host_firewall_policy_ok ${uniqueReasons.length === 0 ? 1 : 0}`,
		"# HELP host_firewall_policy_drift The reason the host firewall check failed.",
		"# TYPE host_firewall_policy_drift gauge",
		...uniqueReasons.map((reason) => `host_firewall_policy_drift{reason="${reason}"} 1`),
		"# HELP host_firewall_policy_check_timestamp_seconds Unix time of the last firewall check.",
		"# TYPE host_firewall_policy_check_timestamp_seconds gauge",
		`host_firewall_policy_check_timestamp_seconds ${seconds}`,
		"",
	].join("\n");
}

function readConfig(config: string): unknown {
	const contents = readFileSync(config, "utf8");
	let data: unknown;
	try {
		data = JSON.parse(contents);
	} catch (error) {
		throw new Error("Invalid firewall configuration JSON.", { cause: error });
	}
	return data;
}

function reportPolicyStatus(config: string, metrics: string) {
	if (!metrics.startsWith("/") || !metrics.endsWith(".prom")) {
		throw new Error("Use an absolute .prom path for the textfile collector.");
	}
	let problems: Problem[];
	try {
		problems = drift(readConfig(config));
	} catch (error) {
		problems = [
			{
				reason: "check_error",
				message: error instanceof Error ? error.message : "Could not check the firewall.",
			},
		];
	}
	for (const { message } of problems) {
		console.error(message);
	}
	const candidate = `${metrics}.${randomUUID()}`;
	try {
		writeFileSync(
			candidate,
			renderPolicyMetrics(
				problems.map(({ reason }) => reason),
				Math.floor(Date.now() / 1000),
			),
			{ flag: "wx", mode: 0o644 },
		);
		chmodSync(candidate, 0o644);
		renameSync(candidate, metrics);
	} finally {
		rmSync(candidate, { force: true });
	}
}

function main() {
	if (process.platform !== "linux" || process.getuid?.() !== 0) {
		throw new Error("Run as root on the Linux host.");
	}
	const { values, positionals } = parseArgs({
		options: {
			check: { type: "boolean" },
			report: { type: "string" },
		},
		allowPositionals: true,
	});
	const [config] = positionals;
	if (
		config === undefined ||
		positionals.length !== 1 ||
		[values.check, values.report !== undefined].filter(Boolean).length !== 1
	) {
		throw new Error(
			"Usage: sudo node scripts/host-firewall.ts (--check|--report METRICS.prom) CONFIG.json",
		);
	}
	if (values.report !== undefined) {
		reportPolicyStatus(config, values.report);
		return;
	}
	const problems = drift(readConfig(config));
	if (problems.length > 0) {
		throw new Error(problems.map(({ message }) => message).join("\n"));
	}
	console.log("UFW matches the host policy.");
}

if (typeof import.meta.main !== "boolean") {
	throw new Error("Use Node.js 24.2 or later.");
}

if (import.meta.main) {
	try {
		main();
	} catch (error) {
		if (error instanceof SyntaxError) {
			console.error("Invalid firewall configuration JSON.");
		} else {
			console.error(error instanceof Error ? error.message : "Firewall operation failed.");
		}
		process.exitCode = 1;
	}
}
