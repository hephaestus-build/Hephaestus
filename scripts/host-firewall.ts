import { execFileSync, spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
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
	unlinkSync,
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
const anyAddress = { 4: "0.0.0.0/0", 6: "::/0" };
const defaults = [
	{ chain: "INPUT", direction: "incoming", ufw: "deny", policy: "DROP" },
	{ chain: "OUTPUT", direction: "outgoing", ufw: "allow", policy: "ACCEPT" },
	{ chain: "FORWARD", direction: "routed", ufw: "deny", policy: "DROP" },
] as const;
const lock = "/run/hephaestus-host-firewall.lock";
const pending = "/run/hephaestus-host-firewall.pending";
const rollback = "hephaestus-host-firewall-rollback";
const recoveryDirectory = "/run/hephaestus-host-firewall-recovery";
const recoveryChain = "HEPHAESTUS-RECOVERY";
const ufw = "/usr/sbin/ufw";
const systemctl = "/usr/bin/systemctl";
const ufwDefaults = "/etc/default/ufw";
const ufwFiles = {
	defaults: ufwDefaults,
	"user.rules": "/etc/ufw/user.rules",
	"user6.rules": "/etc/ufw/user6.rules",
	"ufw.conf": "/etc/ufw/ufw.conf",
};
const tuplePrefix = "### tuple ### ";

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

function key(rule: Rule, family: Family): string {
	return [
		"allow",
		rule.protocol ?? "any",
		rule.port ?? "any",
		anyAddress[family],
		"any",
		rule.source ?? anyAddress[family],
		rule.device === undefined ? "in" : `in_${rule.device}`,
	].join(" ");
}

export function savedRules(contents: string, family: Family): string[] {
	const lines = contents.split("\n");
	const start = lines.indexOf("### RULES ###");
	const end = lines.indexOf("### END RULES ###");
	if (start === -1 || end <= start) {
		throw new Error(`Cannot read the saved IPv${family} UFW rules.`);
	}
	return lines
		.slice(start + 1, end)
		.filter((line) => line.startsWith(tuplePrefix))
		.map((line) => {
			const tokens = line
				.slice(tuplePrefix.length)
				.split(" ")
				.filter((token) => !token.startsWith("comment="));
			// UFW writes a host-length prefix in the saved tuple. Accept either representation.
			const source = tokens[5];
			if (source !== undefined && isIP(source)) {
				tokens[5] = `${source}/${family === 4 ? "32" : "128"}`;
			}
			return tokens.join(" ");
		});
}

export function ruleChanges(rules: Rule[], current: Record<Family, string[]>) {
	const keep = new Set<string>();
	const remove: number[] = [];
	let index = 0;
	for (const family of families) {
		const expected = new Set(
			rules
				.filter((rule) => rule.family === undefined || rule.family === family)
				.map((rule) => key(rule, family)),
		);
		for (const rule of current[family]) {
			index += 1;
			const entry = `${family}:${rule}`;
			if (expected.has(rule) && !keep.has(entry)) {
				keep.add(entry);
			} else {
				remove.push(index);
			}
		}
	}
	const add = rules.filter((rule) =>
		families.some(
			(family) =>
				(rule.family === undefined || rule.family === family) &&
				!keep.has(`${family}:${key(rule, family)}`),
		),
	);
	return { add, remove: remove.toReversed() };
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

function saved(): Record<Family, string[]> {
	return {
		4: savedRules(readFileSync("/etc/ufw/user.rules", "utf8"), 4),
		6: savedRules(readFileSync("/etc/ufw/user6.rules", "utf8"), 6),
	};
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
	return problems;
}

function unmanagedInputRule(line: string, prefix: string, family: Family) {
	const [action, chain] = line.split(" ");
	const uncommented = line.replaceAll(/"(?:[^"\\]|\\.)*"/gu, "");
	const target = / -[jg] (?<jump>\S+)/u.exec(uncommented)?.groups?.jump;
	if (
		action !== "-A" ||
		target === undefined ||
		["DROP", "REJECT", "RETURN", "LOG"].includes(target)
	) {
		return false;
	}
	if (chain === "INPUT") {
		// kube-router marks traffic already admitted by its NetworkPolicies.
		const policyRule = line.replaceAll(/ -m comment --comment "(?:[^"\\]|\\.)*"/gu, "");
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
			!(target === "ACCEPT" && standardBeforeInputAccept(line.slice(`-A ${chain} `.length), family))
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

function drift(rules: Rule[]): Problem[] {
	const changes = ruleChanges(rules, saved());
	const problems = settingsDrift(
		readFileSync(ufwDefaults, "utf8"),
		readFileSync(ufwFiles["ufw.conf"], "utf8"),
		unitState("ufw.service", "UnitFileState"),
	);
	const snapshot = snapshotDrift();
	if (snapshot !== null) {
		problems.push({ reason: "netfilter_persistent", message: snapshot });
	}
	if (changes.add.length > 0 || changes.remove.length > 0) {
		problems.push({
			reason: "saved_rules",
			message: `Saved UFW rules differ (${changes.add.length} missing, ${changes.remove.length} extra).`,
		});
	}
	for (const family of families) {
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

function ensureIpv6() {
	const contents = readFileSync(ufwDefaults, "utf8");
	if (setting(contents, "IPV6") === "yes") {
		return;
	}
	if (!["no", "yes"].includes(setting(contents, "IPV6") ?? "")) {
		throw new Error("Expected one IPV6 setting in /etc/default/ufw.");
	}
	replaceFile(ufwDefaults, contents.replace(/^IPV6=.*$/mu, "IPV6=yes"), statSync(ufwDefaults));
	// UFW needs its IPv6 chains before active rules can be changed through the CLI.
	if (setting(readFileSync("/etc/ufw/ufw.conf", "utf8"), "ENABLED") === "yes") {
		run(ufw, ["reload"]);
	}
}

function bootState(state: string) {
	if (state !== "enabled" && state !== "enabled-runtime" && state !== "disabled") {
		throw new Error("Expected ufw.service to be enabled, enabled at runtime, or disabled.");
	}
	return state;
}

function replaceFile(
	path: string,
	contents: string | Buffer,
	metadata: { uid: number; gid: number; mode: number },
) {
	const temporary = `${path}.hephaestus-${randomUUID()}`;
	try {
		writeFileSync(temporary, contents, { flag: "wx", mode: 0o600 });
		chownSync(temporary, metadata.uid, metadata.gid);
		chmodSync(temporary, metadata.mode % 0o1000);
		renameSync(temporary, path);
	} finally {
		rmSync(temporary, { force: true });
	}
}

export function filesDigest(files: typeof ufwFiles) {
	const hash = createHash("sha256");
	for (const path of Object.values(files)) {
		const metadata = statSync(path);
		const contents = readFileSync(path);
		hash.update(
			JSON.stringify([metadata.uid, metadata.gid, metadata.mode % 0o1000, contents.length]),
		);
		hash.update(contents);
	}
	return hash.digest("hex");
}

function savedFiles(directory: string): typeof ufwFiles {
	return {
		defaults: nodePath.join(directory, "defaults"),
		"user.rules": nodePath.join(directory, "user.rules"),
		"user6.rules": nodePath.join(directory, "user6.rules"),
		"ufw.conf": nodePath.join(directory, "ufw.conf"),
	};
}

const packetFilter = { 4: "/usr/sbin/iptables", 6: "/usr/sbin/ip6tables" } as const;

export function managedLiveState(enabled: boolean, execute: typeof run) {
	return families.map((family) => {
		const chains = new Map<string, string[]>();
		for (const line of execute(packetFilter[family], ["-w", "5", "-S"]).split("\n")) {
			const [action, chain] = line.split(" ");
			const jumpToUfw = / -j ufw6?-[^ ]+$/u.test(line);
			// A disable can leave empty UFW chains. Neither those chains nor their jumps filter traffic.
			if (!enabled && (action === "-N" || jumpToUfw)) {
				continue;
			}
			if (chain !== undefined && (action === "-P" || /^ufw6?-/u.test(chain) || jumpToUfw)) {
				const rules = chains.get(chain) ?? [];
				rules.push(line);
				chains.set(chain, rules);
			}
		}
		return [...chains].toSorted(([left], [right]) => left.localeCompare(right));
	});
}

interface RecoveryRuntime {
	directory: string;
	pending: string;
	files: typeof ufwFiles;
	execute: typeof run;
	writePolicy: (rules: Rule[]) => void;
	drift: (rules: Rule[]) => Problem[];
}

const hostRecovery: RecoveryRuntime = {
	directory: recoveryDirectory,
	pending,
	files: ufwFiles,
	execute: run,
	writePolicy,
	drift,
};

function ufwUnitState(execute: typeof run) {
	return execute(systemctl, ["show", "--value", "--property=UnitFileState", "ufw.service"]);
}

function snapshotUfw(rules: Rule[], { directory, files, execute }: RecoveryRuntime) {
	const state = bootState(ufwUnitState(execute));
	const snapshotDirectory = nodePath.join(directory, "snapshot");
	mkdirSync(directory, { mode: 0o700 });
	try {
		mkdirSync(snapshotDirectory, { mode: 0o700 });
		for (const [name, path] of Object.entries(files)) {
			const backup = nodePath.join(snapshotDirectory, name);
			const original = statSync(path);
			copyFileSync(path, backup);
			chownSync(backup, original.uid, original.gid);
		}
		writeFileSync(nodePath.join(snapshotDirectory, "ufw.service.state"), state, { mode: 0o600 });
		// Refuse an apply if there is no known enabled state to restore.
		const enabled = savedEnabled(readFileSync(savedFiles(snapshotDirectory)["ufw.conf"], "utf8"));
		const expectedFiles = filesDigest(files);
		if (filesDigest(savedFiles(snapshotDirectory)) !== expectedFiles) {
			throw new Error("UFW files changed while taking the recovery snapshot.");
		}
		writeFileSync(nodePath.join(directory, "expected-files"), expectedFiles, {
			mode: 0o600,
		});
		writeFileSync(
			nodePath.join(directory, "expected-live"),
			JSON.stringify([state, managedLiveState(enabled, execute)]),
			{ mode: 0o600 },
		);
		writeFileSync(
			nodePath.join(directory, "vpn.json"),
			JSON.stringify(rules.filter(({ port }) => port === 22).map(({ source }) => source)),
			{ mode: 0o600 },
		);
		copyFileSync(import.meta.filename, nodePath.join(directory, "host-firewall.ts"));
	} catch (error) {
		rmSync(directory, { recursive: true, force: true });
		throw error;
	}
}

function savedEnabled(contents: string) {
	const enabled = setting(contents, "ENABLED");
	if (enabled !== "yes" && enabled !== "no") {
		throw new Error("Expected ENABLED=yes or ENABLED=no in the UFW snapshot.");
	}
	return enabled === "yes";
}

export function recoveryCommand(snapshotDir: string): string[] {
	return [
		"/usr/bin/flock",
		lock,
		process.execPath,
		nodePath.join(snapshotDir, "host-firewall.ts"),
		"--restore-ufw",
	];
}

export function restoreSnapshot(snapshotDir: string, files: typeof ufwFiles, execute: typeof run) {
	const enabled = savedEnabled(readFileSync(nodePath.join(snapshotDir, "ufw.conf"), "utf8"));
	const state = bootState(readFileSync(nodePath.join(snapshotDir, "ufw.service.state"), "utf8"));
	const copies = Object.entries(files).map(([name, path]) => {
		const backup = nodePath.join(snapshotDir, name);
		return { path, bytes: readFileSync(backup), metadata: statSync(backup) };
	});

	// When reverting a first enable, remove the live IPv6 rules before restoring the old defaults.
	if (!enabled) {
		execute(ufw, ["--force", "disable"]);
	}
	for (const { path, bytes, metadata } of copies) {
		replaceFile(path, bytes, metadata);
	}
	if (enabled) {
		execute(ufw, ["reload"]);
	}
	if (state !== "enabled") {
		execute(systemctl, ["disable", "ufw.service"]);
		if (state === "enabled-runtime") {
			execute(systemctl, ["enable", "--runtime", "ufw.service"]);
		}
	}
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

function recoveryVpn(directory: string) {
	try {
		const config: unknown = JSON.parse(readFileSync(nodePath.join(directory, "vpn.json"), "utf8"));
		return sources(config, "recovery VPN networks", true).map((rule) => ({
			...rule,
			port: 22,
			protocol: "tcp" as const,
		}));
	} catch {
		console.error("Recovery could not read the VPN networks. Use the host console.");
		return [];
	}
}

function preserveRecoveryFilters(defaultsPath: string) {
	const configuration = readFileSync(defaultsPath, "utf8");
	if (setting(configuration, "MANAGE_BUILTINS") === "yes") {
		replaceFile(
			defaultsPath,
			configuration.replace(/^MANAGE_BUILTINS=.*$/mu, "MANAGE_BUILTINS=no"),
			statSync(defaultsPath),
		);
	}
}

function failClosed(runtime: RecoveryRuntime) {
	const { directory, files, execute } = runtime;
	const vpn = recoveryVpn(directory);
	installRecoveryFilters(vpn, execute);
	// Keep the restrictive live guards while UFW writes an emergency boot policy.
	try {
		preserveRecoveryFilters(files.defaults);
		runtime.writePolicy(vpn);
	} finally {
		installRecoveryFilters(vpn, execute);
	}
	const problems = runtime.drift(vpn);
	if (problems.length > 0) {
		throw new Error(
			[
				"Could not verify the emergency UFW boot policy.",
				...problems.map(({ message }) => message),
			].join("\n"),
		);
	}
}

export function verifyRestoredPolicy(
	expectedFiles: string,
	expectedLive: string,
	files: typeof ufwFiles,
	execute: typeof run,
) {
	const enabled = savedEnabled(readFileSync(files["ufw.conf"], "utf8"));
	const state = ufwUnitState(execute);
	if (
		filesDigest(files) !== expectedFiles ||
		JSON.stringify([state, managedLiveState(enabled, execute)]) !== expectedLive
	) {
		throw new Error("The restored UFW policy differs from the saved snapshot.");
	}
}

export function restoreUfw(runtime = hostRecovery) {
	const { directory, execute, files } = runtime;
	if (!existsSync(directory)) {
		throw new Error("There is no UFW recovery snapshot.");
	}
	const snapshotDirectory = nodePath.join(directory, "snapshot");
	try {
		const expectedFiles = readFileSync(nodePath.join(directory, "expected-files"), "utf8");
		const expectedLive = readFileSync(nodePath.join(directory, "expected-live"), "utf8");
		const snapshot = savedFiles(snapshotDirectory);
		if (filesDigest(snapshot) !== expectedFiles) {
			throw new Error("The saved UFW files differ from the snapshot.");
		}
		// Keep the guard in place until the original inactive state has been verified.
		if (!savedEnabled(readFileSync(snapshot["ufw.conf"], "utf8"))) {
			installRecoveryFilters(recoveryVpn(directory), execute);
			preserveRecoveryFilters(files.defaults);
		}
		restoreSnapshot(snapshotDirectory, files, execute);
		verifyRestoredPolicy(expectedFiles, expectedLive, files, execute);
		removeRecoveryFilter(execute);
	} catch (error) {
		console.error(error instanceof Error ? error.message : "Could not restore UFW.");
		failClosed(runtime);
		throw new Error("UFW recovery applied an emergency policy. Use the host console.", {
			cause: error,
		});
	}
	rmSync(runtime.pending, { force: true });
	rmSync(directory, { recursive: true });
	console.log("Restored the saved UFW policy.");
}

export function armRecovery(rules: Rule[], runtime = hostRecovery) {
	snapshotUfw(rules, runtime);
	// Hold the same lock in recovery so a slow UFW operation cannot race the restore.
	runtime.execute("/usr/bin/systemd-run", [
		`--unit=${rollback}`,
		"--on-active=10min",
		"--timer-property=AccuracySec=1s",
		"--timer-property=RemainAfterElapse=no",
		"--collect",
		"--setenv=HEPHAESTUS_FIREWALL_LOCKED=1",
		...recoveryCommand(runtime.directory),
	]);
	rmSync(runtime.pending, { force: true });
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

function writePolicy(rules: Rule[]) {
	ensureIpv6();
	const changes = ruleChanges(rules, saved());
	for (const rule of changes.add) {
		run(ufw, ufwArgs(rule));
	}
	for (const index of ruleChanges(rules, saved()).remove) {
		run(ufw, ["--force", "delete", String(index)]);
	}
	const configuration = readFileSync(ufwDefaults, "utf8");
	for (const { chain, direction, policy, ufw: action } of defaults) {
		if (setting(configuration, `DEFAULT_${chain}_POLICY`) !== policy) {
			run(ufw, ["default", action, direction]);
		}
	}
	run(ufw, ["--force", "enable"]);
	run(systemctl, ["enable", "ufw.service"]);
}

function apply(rules: Rule[]) {
	const snapshot = snapshotDrift();
	if (snapshot !== null) {
		throw new Error(snapshot);
	}
	sshConnection(rules);
	const recoveryAction = applyAction(
		unitState(`${rollback}.timer`, "SubState") === "waiting",
		existsSync(pending),
		unitState(`${rollback}.service`, "ActiveState"),
		() => drift(rules).length === 0,
	);
	if (recoveryAction === "retry" && !existsSync(recoveryDirectory)) {
		throw new Error("There is no UFW recovery snapshot for the waiting timer.");
	}
	if (recoveryAction !== "retry" && existsSync(recoveryDirectory)) {
		throw new Error("An unfinished UFW recovery snapshot exists. Restore it from the console.");
	}
	if (recoveryAction === "unchanged") {
		console.log("UFW matches the host policy.");
		return;
	}
	if (recoveryAction === "arm") {
		armRecovery(rules);
	}
	// A retry after an incomplete apply uses the original timer and its remaining time.
	console.log("The recovery timer will restore the saved UFW policy at its next run:");
	console.log(run(systemctl, ["list-timers", "--no-pager", `${rollback}.timer`]));
	writePolicy(rules);

	const problems = drift(rules);
	if (problems.length > 0) {
		throw new Error(problems.map(({ message }) => message).join("\n"));
	}
	if (unitState(`${rollback}.timer`, "SubState") !== "waiting") {
		throw new Error("The recovery timer has expired.");
	}
	const changedAt = readFileSync("/proc/uptime", "utf8").split(" ")[0];
	if (changedAt === undefined || !Number.isFinite(Number(changedAt))) {
		throw new Error("Cannot record when the firewall changed.");
	}
	writeFileSync(pending, changedAt, { mode: 0o600, flag: "wx" });
	console.log("Open a new SSH connection and run --confirm before the recovery timer expires.");
}

function confirm(rules: Rule[]) {
	if (!existsSync(pending)) {
		throw new Error("There is no firewall change to confirm. Run --check or --apply.");
	}
	const changedAt = Number(readFileSync(pending, "utf8"));
	requireFreshSshConnection(changedAt, () => sshConnection(rules));
	const problems = drift(rules);
	if (problems.length > 0 || unitState(`${rollback}.timer`, "SubState") !== "waiting") {
		throw new Error(
			["Cannot confirm the firewall change.", ...problems.map(({ message }) => message)].join("\n"),
		);
	}
	run(systemctl, ["stop", `${rollback}.timer`]);
	// The timer can have started the service while we still hold the lock.
	try {
		run(systemctl, ["stop", `${rollback}.service`]);
	} catch (error) {
		if (unitState(`${rollback}.service`, "LoadState") !== "not-found") {
			throw error;
		}
	}
	unlinkSync(pending);
	rmSync(recoveryDirectory, { recursive: true, force: true });
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

function readPolicy(config: string): Rule[] {
	const contents = readFileSync(config, "utf8");
	let data: unknown;
	try {
		data = JSON.parse(contents);
	} catch (error) {
		throw new Error("Invalid firewall configuration JSON.", { cause: error });
	}
	return rulesFor(data);
}

function reportPolicyStatus(config: string, metrics: string) {
	if (!metrics.startsWith("/") || !metrics.endsWith(".prom")) {
		throw new Error("Use an absolute .prom path for the textfile collector.");
	}
	let problems: Problem[];
	try {
		problems = drift(readPolicy(config));
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
	const rules = readPolicy(config);
	if (values.check === true) {
		const problems = drift(rules);
		if (problems.length > 0) {
			throw new Error(problems.map(({ message }) => message).join("\n"));
		}
		console.log("UFW matches the host policy.");
	} else if (values.apply === true) {
		apply(rules);
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
