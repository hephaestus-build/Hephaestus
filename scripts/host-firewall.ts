import { execFileSync, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import {
	chmodSync,
	existsSync,
	readFileSync,
	renameSync,
	rmSync,
	statSync,
	unlinkSync,
	writeFileSync,
} from "node:fs";
import { BlockList, isIP } from "node:net";
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
const ufw = "/usr/sbin/ufw";
const systemctl = "/usr/bin/systemctl";
const ufwDefaults = "/etc/default/ufw";
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
	return problems;
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

function drift(rules: Rule[]): Problem[] {
	const configuration = readFileSync(ufwDefaults, "utf8");
	const changes = ruleChanges(rules, saved());
	const problems: Problem[] = [];
	const snapshot = snapshotDrift();
	if (snapshot !== null) {
		problems.push({ reason: "netfilter_persistent", message: snapshot });
	}
	if (setting(configuration, "IPV6") !== "yes") {
		problems.push({ reason: "ipv6_disabled", message: "UFW IPv6 support is disabled." });
	}
	if (
		setting(readFileSync("/etc/ufw/ufw.conf", "utf8"), "ENABLED") !== "yes" ||
		unitState("ufw.service", "UnitFileState") !== "enabled"
	) {
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
	if (changes.add.length > 0 || changes.remove.length > 0) {
		problems.push({
			reason: "saved_rules",
			message: `Saved UFW rules differ (${changes.add.length} missing, ${changes.remove.length} extra).`,
		});
	}
	for (const family of families) {
		const command = family === 4 ? "/usr/sbin/iptables" : "/usr/sbin/ip6tables";
		for (const message of liveDrift(run(command, ["-w", "5", "-S"]).split("\n"), family, rules)) {
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
	const file = `${ufwDefaults}.hephaestus-${process.pid}`;
	writeFileSync(file, contents.replace(/^IPV6=.*$/mu, "IPV6=yes"), {
		flag: "wx",
		mode: statSync(ufwDefaults).mode % 0o1000,
	});
	renameSync(file, ufwDefaults);
	// UFW needs its IPv6 chains before active rules can be changed through the CLI.
	if (setting(readFileSync("/etc/ufw/ufw.conf", "utf8"), "ENABLED") === "yes") {
		run(ufw, ["reload"]);
	}
}

function armRecovery() {
	// Hold the same lock in recovery so a slow UFW operation cannot race the disable.
	run("/usr/bin/systemd-run", [
		`--unit=${rollback}`,
		"--on-active=10min",
		"--timer-property=AccuracySec=1s",
		"--timer-property=RemainAfterElapse=no",
		"--collect",
		"/usr/bin/flock",
		lock,
		ufw,
		"--force",
		"disable",
	]);
	rmSync(pending, { force: true });
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
	if (recoveryAction === "unchanged") {
		console.log("UFW matches the host policy.");
		return;
	}
	if (recoveryAction === "arm") {
		armRecovery();
	}
	// A retry after an incomplete apply uses the original timer and its remaining time.
	console.log("The recovery timer will disable UFW at its next run:");
	console.log(run(systemctl, ["list-timers", "--no-pager", `${rollback}.timer`]));
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
	if (process.platform !== "linux" || process.getuid?.() !== 0) {
		throw new Error("Run as root on the Linux host.");
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
		// Only policy changes and confirmation take the write lock.
		if (
			(process.argv.includes("--apply") || process.argv.includes("--confirm")) &&
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
