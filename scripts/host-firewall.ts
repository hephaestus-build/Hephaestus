import { execFileSync, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import {
	chmodSync,
	chownSync,
	copyFileSync,
	existsSync,
	mkdirSync,
	readFileSync,
	renameSync,
	rmSync,
	statSync,
	writeFileSync,
} from "node:fs";
import { BlockList, isIP } from "node:net";
import nodePath from "node:path";
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
	{ chain: "INPUT", direction: "incoming", ufw: "deny", policy: "DROP" },
	{ chain: "OUTPUT", direction: "outgoing", ufw: "allow", policy: "ACCEPT" },
	{ chain: "FORWARD", direction: "routed", ufw: "deny", policy: "DROP" },
] as const;
const lock = "/run/hephaestus-host-firewall.lock";
const pending = "/run/hephaestus-host-firewall.pending";
const confirmed = "/etc/hephaestus/host-firewall-confirmed.json";
const rollback = "hephaestus-host-firewall-rollback";
const recoveryDirectory = "/run/hephaestus-host-firewall-recovery";
const recoveryChain = "HEPHAESTUS-RECOVERY";
const ufw = "/usr/sbin/ufw";
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
		Object.keys(config).length !== 4
	) {
		throw new Error(
			"Use sshVpnCidrs, monitoringAddresses, k3s and http3 in the firewall configuration.",
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
		...(config.k3s
			? [
					{ device: "cni0" },
					{ device: "flannel.1" },
					{ family: 4 as const, source: "10.42.0.0/16" },
					{ family: 4 as const, source: "10.43.0.0/16" },
				]
			: []),
	];
}

export function ufwArgs(rule: Rule): string[] {
	if (rule.device !== undefined) {
		return ["allow", "in", "on", rule.device];
	}
	if (rule.source === undefined) {
		return ["allow", `${String(rule.port)}/${String(rule.protocol)}`];
	}
	if (rule.port === undefined) {
		return ["allow", "from", rule.source];
	}
	return [
		"allow",
		"from",
		rule.source,
		"to",
		"any",
		"port",
		String(rule.port),
		"proto",
		String(rule.protocol),
	];
}

function setting(contents: string, name: string): string | undefined {
	const entries = contents.split("\n").filter((line) => line.startsWith(`${name}=`));
	return entries.length === 1
		? entries[0]?.slice(name.length + 1).replace(/^"(?<value>[^"]*)"$/u, "$1")
		: undefined;
}

function run(command: string, args: string[], input?: string): string {
	try {
		return execFileSync(command, args, {
			encoding: "utf8",
			env: { ...process.env, LC_ALL: "C" },
			timeout: 15_000,
			maxBuffer: 16 * 1024 * 1024,
			input,
			stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
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

export function liveDrift(
	lines: string[],
	family: Family,
	rules: Rule[],
	guarded = false,
): string[] {
	const prefix = family === 4 ? "ufw" : "ufw6";
	const input = `${prefix}-user-input`;
	const wanted = rules
		.filter((rule) => rule.family === undefined || rule.family === family)
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
	if (lines.some((line) => unmanagedInputRule(line, prefix, family))) {
		problems.push(`IPv${family} has an unmanaged input rule.`);
	}
	if (!guarded && lines.includes(`-A INPUT -j ${recoveryChain}`)) {
		problems.push(`IPv${family} has an active emergency firewall guard.`);
	}
	if (
		deniesBefore(lines, "INPUT", `${prefix}-before-input`) ||
		deniesBefore(lines, `${prefix}-before-input`, input)
	) {
		problems.push(`IPv${family} has an input deny before the required UFW rules.`);
	}
	return problems;
}

function deniesBefore(lines: string[], chain: string, next: string) {
	const hook = lines.indexOf(`-A ${chain} -j ${next}`);
	return lines.slice(0, Math.max(hook, 0)).some((line) => {
		const rule = withoutComment(line);
		return (
			rule.startsWith(`-A ${chain} `) &&
			/ -[jg] (?:DROP|REJECT|RETURN)(?: |$)/u.test(rule) &&
			rule !== `-A ${chain} -m conntrack --ctstate INVALID -j DROP` &&
			rule !== `-A ${chain} -m rt --rt-type 0 -j DROP`
		);
	});
}

function withoutComment(line: string) {
	return line.replaceAll(/ -m comment --comment (?:"(?:[^"\\]|\\.)*"|[^"\s]+)/gu, "");
}

function unmanagedInputRule(line: string, prefix: string, family: Family) {
	const [action, chain] = line.split(" ");
	const policyRule = withoutComment(line);
	const target = / -[jg] (?<jump>\S+)/u.exec(policyRule)?.groups?.jump;
	if (
		action !== "-A" ||
		target === undefined ||
		["DROP", "REJECT", "RETURN", "LOG"].includes(target)
	) {
		return false;
	}
	if (chain === "INPUT") {
		// kube-router marks traffic already admitted by its NetworkPolicies.
		if (
			policyRule === "-A INPUT -m mark --mark 0x20000/0x20000 -j ACCEPT" ||
			policyRule === "-A INPUT -i lo -j ACCEPT"
		) {
			return false;
		}
		return ![
			"KUBE-ROUTER-INPUT",
			"KUBE-FIREWALL",
			"KUBE-PROXY-FIREWALL",
			"KUBE-NODEPORTS",
			"KUBE-EXTERNAL-SERVICES",
			"KUBE-SERVICES",
			recoveryChain,
			...["before-logging", "before", "after", "after-logging", "reject", "track"].map(
				(name) => `${prefix}-${name}-input`,
			),
		].includes(target);
	}
	if (chain === `${prefix}-before-input`) {
		return (
			!["logging-deny", "not-local", "user-input"].some((name) => target === `${prefix}-${name}`) &&
			!(
				target === "ACCEPT" &&
				standardBeforeInputAccept(policyRule.slice(`-A ${chain} `.length), family)
			)
		);
	}
	return chain === `${prefix}-after-input` && target !== `${prefix}-skip-to-policy-input`;
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

function drift(rules: Rule[], guarded = false): Problem[] {
	const problems = settingsDrift(
		readFileSync(ufwDefaults, "utf8"),
		readFileSync(ufwFiles["ufw.conf"], "utf8"),
		unitState("ufw.service", "UnitFileState"),
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
			guarded,
		)) {
			problems.push({ reason: "live_rules", message });
		}
	}
	return problems;
}

function sshConnection(rules: Rule[]) {
	const connection = process.env.SSH_CONNECTION ?? "";
	const [peer = "", peerPort, local, port, extra] = connection.split(" ");
	if (
		port !== "22" ||
		!/^\d+$/u.test(peerPort ?? "") ||
		!isIP(local ?? "") ||
		extra !== undefined
	) {
		throw new Error("Use SSH on port 22 and preserve SSH_CONNECTION through sudo.");
	}
	const family = addressFamily(peer, "SSH_CONNECTION");
	const vpn = new BlockList();
	const sshRules = rules.filter((rule) => rule.port === 22 && rule.family === family);
	for (const rule of sshRules) {
		const [network = "", prefix] = rule.source?.split("/") ?? [];
		vpn.addSubnet(network, Number(prefix), family === 4 ? "ipv4" : "ipv6");
	}
	if (!vpn.check(peer, family === 4 ? "ipv4" : "ipv6")) {
		throw new Error("The SSH client is outside the configured VPN networks.");
	}
	return sshStartedAt(process.ppid, Number(run("/usr/bin/getconf", ["CLK_TCK"])), (pid) =>
		readFileSync(`/proc/${pid}/stat`, "utf8"),
	);
}

export function sshStartedAt(
	parentPid: number,
	tickRate: number,
	readStat: (pid: number) => string,
) {
	// OpenSSH keeps a process for the TCP connection, even when a client multiplexes new sessions.
	let pid = parentPid;
	let started = 0;
	while (pid > 1) {
		const stat = readStat(pid);
		const nameEnd = stat.lastIndexOf(")");
		const name = stat.slice(stat.indexOf("(") + 1, nameEnd);
		const fields = stat.slice(nameEnd + 2).split(" ");
		pid = Number(fields[1]);
		if (pid > 1 && ["sshd", "sshd-session", "sshd-auth"].includes(name)) {
			started = Number(fields[19]) / tickRate;
		}
	}
	if (!Number.isFinite(started) || started <= 0) {
		throw new Error("Cannot verify the age of this SSH connection.");
	}
	return started;
}

export function requireFreshSshConnection(changedAt: number, startedAt: () => number) {
	if (!Number.isFinite(changedAt) || changedAt <= 0 || startedAt() <= changedAt) {
		throw new Error("Open a new SSH connection before confirming.");
	}
}

function replaceFile(
	path: string,
	contents: string,
	metadata?: { uid: number; gid: number; mode: number },
) {
	const temporary = `${path}.hephaestus-${randomUUID()}`;
	try {
		writeFileSync(temporary, contents, { flag: "wx", mode: 0o600 });
		if (metadata !== undefined) {
			chownSync(temporary, metadata.uid, metadata.gid);
			chmodSync(temporary, metadata.mode % 0o1000);
		}
		renameSync(temporary, path);
	} finally {
		rmSync(temporary, { force: true });
	}
}

const packetFilter = { 4: "/usr/sbin/iptables", 6: "/usr/sbin/ip6tables" } as const;

interface Change {
	id: string;
	policy: unknown;
	started?: true;
	changedAt?: number;
}

interface RecoveryRuntime {
	directory: string;
	pending: string;
	confirmed: string;
	defaultsPath: string;
	execute: typeof run;
	drift: (rules: Rule[], guarded?: boolean) => Problem[];
}

const hostRecovery: RecoveryRuntime = {
	directory: recoveryDirectory,
	pending,
	confirmed,
	defaultsPath: ufwDefaults,
	execute: run,
	drift,
};

function readChange(file: string): Change | undefined {
	if (!existsSync(file)) {
		return undefined;
	}
	const data: unknown = JSON.parse(readFileSync(file, "utf8"));
	if (
		typeof data !== "object" ||
		data === null ||
		!("id" in data) ||
		typeof data.id !== "string" ||
		!("policy" in data) ||
		("started" in data && data.started !== true) ||
		("changedAt" in data &&
			(typeof data.changedAt !== "number" || !Number.isFinite(data.changedAt)))
	) {
		throw new Error("Invalid saved firewall policy declaration.");
	}
	rulesFor(data.policy);
	return {
		id: data.id,
		policy: data.policy,
		...("started" in data && data.started === true ? { started: true as const } : {}),
		...("changedAt" in data && typeof data.changedAt === "number"
			? { changedAt: data.changedAt }
			: {}),
	};
}

function saveChange(file: string, change: Change) {
	replaceFile(file, JSON.stringify(change));
}

export function recoveryCommand(directory: string): string[] {
	return [
		"/usr/bin/flock",
		lock,
		process.execPath,
		nodePath.join(directory, "host-firewall.ts"),
		"--restore-ufw",
	];
}

export function recoveryFilter(family: Family, vpn: Rule[], existing: string) {
	const forwardChain = `${recoveryChain}-FORWARD`;
	const inputHook = `INPUT -j ${recoveryChain}`;
	const forwardHook = `FORWARD -j ${forwardChain}`;
	const hooks = new Set([inputHook, forwardHook]);
	const previousHooks = existing
		.split("\n")
		.filter((line) => line.startsWith("-A ") && hooks.has(line.slice(3)))
		.map((line) => `-D ${line.slice(3)}`);
	return [
		"*filter",
		`:${recoveryChain} - [0:0]`,
		`:${forwardChain} - [0:0]`,
		"-P INPUT DROP",
		"-P FORWARD DROP",
		...previousHooks,
		`-A ${recoveryChain} -i lo -j ACCEPT`,
		// Router advertisements, neighbor discovery and packet-too-big keep IPv6 SSH usable.
		...(family === 6
			? [2, 134, 135, 136].map(
					(type) => `-A ${recoveryChain} -p ipv6-icmp --icmpv6-type ${type} -j ACCEPT`,
				)
			: []),
		...vpn.flatMap(({ family: ruleFamily, source }) =>
			ruleFamily === family && source !== undefined
				? [`-A ${recoveryChain} -s ${source} -p tcp --dport 22 -j ACCEPT`]
				: [],
		),
		`-A ${recoveryChain} -j DROP`,
		`-A ${forwardChain} -j DROP`,
		`-I INPUT 1 -j ${recoveryChain}`,
		`-I FORWARD 1 -j ${forwardChain}`,
		"COMMIT",
		"",
	].join("\n");
}

function removeRecoveryFilter(execute: typeof run) {
	for (const family of families) {
		const command = packetFilter[family];
		const rules = execute(command, ["-w", "5", "-S"]).split("\n");
		for (const [chain, base] of [
			[recoveryChain, "INPUT"],
			[`${recoveryChain}-FORWARD`, "FORWARD"],
		] as const) {
			if (rules.includes(`-A ${base} -j ${chain}`)) {
				execute(command, ["-w", "5", "-D", base, "-j", chain]);
				execute(command, ["-w", "5", "-F", chain]);
				execute(command, ["-w", "5", "-X", chain]);
			}
		}
	}
}

function installRecoveryFilters(vpn: Rule[], execute: typeof run) {
	const failures: unknown[] = [];
	for (const family of families) {
		const command = packetFilter[family];
		try {
			const existing = execute(command, ["-w", "5", "-S"]);
			execute(
				`${command}-restore`,
				["--noflush", "--wait", "5"],
				recoveryFilter(family, vpn, existing),
			);
			const rules = execute(command, ["-w", "5", "-S"]).split("\n");
			if (
				!rules.includes("-P INPUT DROP") ||
				!rules.includes("-P FORWARD DROP") ||
				rules.find((line) => line.startsWith("-A INPUT ")) !== `-A INPUT -j ${recoveryChain}` ||
				rules.find((line) => line.startsWith("-A FORWARD ")) !==
					`-A FORWARD -j ${recoveryChain}-FORWARD`
			) {
				throw new Error(`IPv${family} emergency firewall guards differ.`);
			}
		} catch (error) {
			failures.push(error);
		}
	}
	if (failures.length > 0) {
		throw new AggregateError(
			failures,
			"Could not verify fail-closed recovery on both IP families.",
		);
	}
}

function setUfwDefault(path: string, name: "IPV6" | "MANAGE_BUILTINS", value: "yes" | "no") {
	const contents = readFileSync(path, "utf8");
	const previous = setting(contents, name);
	if (previous === undefined) {
		throw new Error(`Expected one ${name} setting in the UFW defaults.`);
	}
	if (previous !== value) {
		replaceFile(
			path,
			contents.replace(new RegExp(`^${name}=.*$`, "mu"), `${name}=${value}`),
			statSync(path),
		);
	}
}

function vpnRules(rules: Rule[]) {
	return rules.filter(({ port }) => port === 22);
}

function verifyPolicy(rules: Rule[], runtime: RecoveryRuntime, guarded = false) {
	const problems = runtime.drift(rules, guarded);
	if (problems.length > 0) {
		throw new Error(problems.map(({ message }) => message).join("\n"));
	}
}

function rebuildPolicy(rules: Rule[], runtime: RecoveryRuntime, emergency = false) {
	const { execute, defaultsPath } = runtime;
	const vpn = vpnRules(rules);
	// UFW reset disables UFW temporarily. Keep blocking input and forwarding through the reset.
	installRecoveryFilters(vpn, execute);
	try {
		setUfwDefault(defaultsPath, "MANAGE_BUILTINS", "no");
		execute(ufw, ["--force", "reset"]);
		setUfwDefault(defaultsPath, "IPV6", "yes");
		for (const { direction, ufw: action } of defaults) {
			execute(ufw, ["default", action, direction]);
		}
		for (const rule of rules) {
			execute(ufw, ufwArgs(rule));
		}
		execute(ufw, ["--force", "enable"]);
		execute(systemctl, ["enable", "ufw.service"]);
		verifyPolicy(rules, runtime, true);
		if (emergency) {
			installRecoveryFilters(vpn, execute);
		} else {
			removeRecoveryFilter(execute);
		}
	} catch (error) {
		installRecoveryFilters(vpn, execute);
		throw error;
	}
}

export function restoreUfw(runtime = hostRecovery) {
	let change: Change | undefined;
	try {
		change = readChange(runtime.pending);
	} catch (error) {
		installRecoveryFilters([], runtime.execute);
		throw new Error("Cannot read the unconfirmed policy. The emergency guards are active.", {
			cause: error,
		});
	}
	if (change === undefined || change.started !== true) {
		return;
	}
	try {
		const last = readChange(runtime.confirmed);
		if (last === undefined) {
			throw new Error("No previous firewall policy has been confirmed.");
		}
		if (last.id !== change.id) {
			rebuildPolicy(rulesFor(last.policy), runtime);
			console.log("Restored the last confirmed UFW policy.");
		}
	} catch (error) {
		console.error(error instanceof Error ? error.message : "Could not restore UFW.");
		rebuildPolicy(vpnRules(rulesFor(change.policy)), runtime, true);
		throw new Error("UFW recovery applied a VPN-only emergency policy. Use the host console.", {
			cause: error,
		});
	}
	rmSync(runtime.pending, { force: true });
}

export function armRecovery(change: Change, runtime = hostRecovery) {
	mkdirSync(runtime.directory, { mode: 0o700, recursive: true });
	copyFileSync(import.meta.filename, nodePath.join(runtime.directory, "host-firewall.ts"));
	saveChange(runtime.pending, change);
	// Recovery uses the same lock. An unarmed change record is safe to replace on the next apply.
	runtime.execute("/usr/bin/systemd-run", [
		`--unit=${rollback}`,
		"--on-active=10min",
		"--timer-property=AccuracySec=1s",
		"--timer-property=RemainAfterElapse=no",
		"--collect",
		"--setenv=HEPHAESTUS_FIREWALL_LOCKED=1",
		...recoveryCommand(runtime.directory),
	]);
}

export function applyAction(
	waiting: boolean,
	hasPendingChange: boolean,
	recoveryState: string,
	policyMatches: () => boolean,
) {
	if ((waiting && hasPendingChange) || ["active", "activating"].includes(recoveryState)) {
		throw new Error("A firewall change is awaiting confirmation or recovery.");
	}
	if (waiting) {
		return "retry";
	}
	return policyMatches() ? "unchanged" : "arm";
}

function apply(policy: unknown, rules: Rule[]) {
	const snapshot = snapshotDrift();
	if (snapshot !== null) {
		throw new Error(snapshot);
	}
	sshConnection(rules);
	const waiting = unitState(`${rollback}.timer`, "SubState") === "waiting";
	const previous = waiting ? readChange(pending) : undefined;
	const recoveryAction = applyAction(
		waiting,
		previous?.changedAt !== undefined,
		unitState(`${rollback}.service`, "ActiveState"),
		() => drift(rules).length === 0,
	);
	const change = { id: previous?.id ?? randomUUID(), policy };
	if (recoveryAction === "unchanged") {
		saveChange(confirmed, change);
		console.log("UFW matches the host policy. Recorded the confirmed policy.");
		return;
	}
	if (recoveryAction === "arm") {
		armRecovery(change);
	} else if (previous === undefined) {
		throw new Error("The waiting recovery unit has no policy declaration.");
	}
	saveChange(pending, { ...change, started: true });
	console.log("The recovery timer will reapply the last confirmed UFW policy at its next run:");
	console.log(run(systemctl, ["list-timers", "--no-pager", `${rollback}.timer`]));
	rebuildPolicy(rules, hostRecovery);
	if (unitState(`${rollback}.timer`, "SubState") !== "waiting") {
		throw new Error("The recovery timer has expired.");
	}
	const changedAt = Number(readFileSync("/proc/uptime", "utf8").split(" ")[0]);
	if (!Number.isFinite(changedAt) || changedAt <= 0) {
		throw new Error("Cannot record when the firewall changed.");
	}
	saveChange(pending, { ...change, started: true, changedAt });
	console.log("Open a new SSH connection and run --confirm before the recovery timer expires.");
}

export function commitPolicy(change: Change, runtime = hostRecovery) {
	// Publish the confirmation before trying to stop either unit. A queued unit then does no work.
	saveChange(runtime.confirmed, { id: change.id, policy: change.policy });
	try {
		runtime.execute(systemctl, ["stop", `${rollback}.timer`]);
		runtime.execute(systemctl, ["stop", `${rollback}.service`]);
	} catch (error) {
		console.error(error instanceof Error ? error.message : "Could not stop the recovery units.");
		return;
	}
	rmSync(runtime.pending, { force: true });
}

function confirm(rules: Rule[]) {
	const change = readChange(pending);
	if (
		change?.changedAt === undefined ||
		JSON.stringify(rulesFor(change.policy)) !== JSON.stringify(rules)
	) {
		throw new Error("There is no matching firewall change to confirm. Run --check or --apply.");
	}
	requireFreshSshConnection(change.changedAt, () => sshConnection(rules));
	verifyPolicy(rules, hostRecovery);
	if (unitState(`${rollback}.timer`, "SubState") !== "waiting") {
		throw new Error("Cannot confirm the firewall change after the recovery timer expires.");
	}
	commitPolicy(change);
	console.log("UFW matches the host policy. The new SSH connection confirmed the change.");
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
		problems = drift(rulesFor(readConfig(config)));
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
	if (process.argv.length === 3 && process.argv[2] === "--restore-ufw") {
		restoreUfw();
		return;
	}
	const { values, positionals } = parseArgs({
		options: {
			check: { type: "boolean" },
			apply: { type: "boolean" },
			confirm: { type: "boolean" },
			report: { type: "string" },
		},
		allowPositionals: true,
	});
	const [config] = positionals;
	if (
		config === undefined ||
		positionals.length !== 1 ||
		[values.check, values.apply, values.confirm, values.report !== undefined].filter(Boolean)
			.length !== 1
	) {
		throw new Error(
			"Usage: sudo node scripts/host-firewall.ts (--check|--apply|--confirm|--report METRICS.prom) CONFIG.json",
		);
	}
	if (values.report !== undefined) {
		reportPolicyStatus(config, values.report);
		return;
	}
	const policy = readConfig(config);
	const rules = rulesFor(policy);
	if (values.check === true) {
		const problems = drift(rules);
		if (problems.length > 0) {
			throw new Error(problems.map(({ message }) => message).join("\n"));
		}
		console.log("UFW matches the host policy.");
	} else if (values.apply === true) {
		apply(policy, rules);
	} else {
		confirm(rules);
	}
}

if (typeof import.meta.main !== "boolean") {
	throw new Error("Use Node.js 24.2 or later.");
}

if (import.meta.main) {
	try {
		// Check and report do not change the firewall.
		if (
			["--apply", "--confirm", "--restore-ufw"].some((mode) => process.argv.includes(mode)) &&
			process.env.HEPHAESTUS_FIREWALL_LOCKED !== "1"
		) {
			const result = spawnSync(
				"/usr/bin/flock",
				[
					"--nonblock",
					"--conflict-exit-code",
					"75",
					lock,
					process.execPath,
					...process.argv.slice(1),
				],
				{
					env: { ...process.env, HEPHAESTUS_FIREWALL_LOCKED: "1" },
					stdio: "inherit",
				},
			);
			if (result.error !== undefined) {
				throw result.error;
			}
			if (result.status === 75) {
				console.error("Another firewall change is running.");
			}
			process.exitCode = result.status ?? 1;
		} else {
			main();
		}
	} catch (error) {
		if (error instanceof SyntaxError) {
			console.error("Invalid firewall configuration JSON.");
		} else {
			console.error(error instanceof Error ? error.message : "Firewall operation failed.");
		}
		process.exitCode = 1;
	}
}
