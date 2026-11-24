import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	applyAction,
	armRecovery,
	commitPolicy,
	conflictingSnapshot,
	liveDrift,
	recoveryCommand,
	recoveryFilter,
	renderPolicyMetrics,
	requireFreshSshConnection,
	restoreUfw,
	rulesFor,
	savedDrift,
	settingsDrift,
	sshStartedAt,
	ufwArgs,
} from "./host-firewall.ts";

const base = {
	sshVpnCidrs: ["192.0.2.0/24", "2001:0DB8:1::/64"],
	monitoringAddresses: ["198.51.100.4", "2001:db8:2::4"],
};

const shared = [
	"allow from 192.0.2.0/24 to any port 22 proto tcp",
	"allow from 2001:db8:1::/64 to any port 22 proto tcp",
	"allow from 198.51.100.4/32 to any port 9100 proto tcp",
	"allow from 2001:db8:2::4/128 to any port 9100 proto tcp",
	"allow 80/tcp",
	"allow 443/tcp",
];

void test("k3s hosts allow only web, restricted services, and the k3s networks", () => {
	assert.deepEqual(
		rulesFor({ ...base, k3s: true, http3: false }).map((rule) => ufwArgs(rule).join(" ")),
		[
			...shared,
			"allow in on cni0",
			"allow in on flannel.1",
			"allow from 10.42.0.0/16",
			"allow from 10.43.0.0/16",
		],
	);
});

void test("a host proxy adds only 443/udp", () => {
	assert.deepEqual(
		rulesFor({ ...base, k3s: false, http3: true }).map((rule) => ufwArgs(rule).join(" ")),
		[...shared, "allow 443/udp"],
	);
});

void test("rejects malformed addresses, default routes and missing VPN networks", () => {
	const config = { ...base, k3s: false, http3: true };
	for (const invalid of [
		{ sshVpnCidrs: ["0.0.0.0/0", "2001:db8:1::/64"] },
		{ sshVpnCidrs: ["192.0.2.2/24", "2001:db8:1::/64"] },
		{ sshVpnCidrs: ["192.0.2.0/24", "2001:db8::/129"] },
		{ sshVpnCidrs: [] },
		{ monitoringAddresses: ["198.51.100.4/24", "2001:db8:2::4"] },
		{ monitoringAddresses: ["198.51.100.4", "::ffff:192.0.2.4"] },
		{ monitoringAddresses: ["198.51.100.4", "fe80::4%eth0"] },
		{ monitoringAddresses: ["198.51.100.4", "2001:db8:2::4", "2001:db8:2::5"] },
		{ http3: "false" },
		{ extra: true },
	]) {
		assert.throws(() => rulesFor({ ...config, ...invalid }));
	}
});

void test("an address family with no VPN or monitoring source gets only the web rules", () => {
	assert.deepEqual(
		rulesFor({ sshVpnCidrs: ["192.0.2.0/24"], monitoringAddresses: [], k3s: false, http3: false }),
		[
			{ family: 4, source: "192.0.2.0/24", port: 22, protocol: "tcp" },
			{ port: 80, protocol: "tcp" },
			{ port: 443, protocol: "tcp" },
		],
	);
});

const matches = () => true;

void test("apply keeps the original recovery deadline on retry and refuses outstanding recovery", () => {
	assert.equal(applyAction(false, false, "inactive", matches), "unchanged");
	assert.equal(
		applyAction(false, false, "inactive", () => false),
		"arm",
	);
	assert.equal(applyAction(true, false, "inactive", matches), "retry");
	assert.throws(() => applyAction(true, true, "inactive", matches), /awaiting confirmation/u);
	for (const service of ["active", "activating"]) {
		assert.throws(() => applyAction(false, false, service, matches), /awaiting confirmation/u);
	}
});

function procStat(pid: number, name: string, parent: number, ticks: number) {
	// Start time is field 22. Populate the preceding fields so an off-by-one reads a different value.
	return `${pid} (${name}) S ${parent} ${Array.from({ length: 17 }, (_, index) => index).join(" ")} ${ticks} 0\n`;
}

void test("confirmation uses the TCP connection age, including for multiplexed SSH", () => {
	for (const session of ["sshd", "sshd-session"]) {
		const ancestry = new Map([
			[51, procStat(51, "sudo", 50, 12_000)],
			[50, procStat(50, "shell (new)", 40, 11_500)],
			[40, procStat(40, session, 20, 10_100)],
			[20, procStat(20, session, 10, 10_000)],
			[10, procStat(10, "sshd", 1, 500)],
		]);
		const startedAt = () => sshStartedAt(51, 100, (pid) => ancestry.get(pid) ?? "");
		assert.equal(startedAt(), 100);
		assert.throws(() => requireFreshSshConnection(110, startedAt), /new SSH connection/u);
		assert.throws(() => requireFreshSshConnection(100, startedAt), /new SSH connection/u);
		assert.doesNotThrow(() => requireFreshSshConnection(99.99, startedAt));
	}
});

void test("a detached terminal cannot confirm a firewall change", () => {
	assert.throws(
		() => sshStartedAt(50, 100, (pid) => procStat(pid, "tmux: server", 1, 11_500)),
		/Cannot verify the age/u,
	);
});

void test("the recovery unit runs the saved script under the same lock as apply", () => {
	assert.deepEqual(recoveryCommand("/run/firewall-test-recovery"), [
		"/usr/bin/flock",
		"/run/hephaestus-host-firewall.lock",
		process.execPath,
		path.join("/run/firewall-test-recovery", "host-firewall.ts"),
		"--restore-ufw",
	]);
});

void test("emergency recovery puts the VPN-only guard before existing accepts", () => {
	const ipv4 = recoveryFilter(
		4,
		[
			{ family: 4, source: "192.0.2.0/24" },
			{ family: 6, source: "2001:db8:1::/64" },
		],
		"-A INPUT -p tcp --dport 6443 -j ACCEPT\n-A INPUT -j HEPHAESTUS-RECOVERY",
	);
	assert.match(ipv4, /^-P INPUT DROP$/mu);
	assert.match(ipv4, /^-P FORWARD DROP$/mu);
	assert.match(ipv4, /^-D INPUT -j HEPHAESTUS-RECOVERY$/mu);
	assert.match(ipv4, /^-I INPUT 1 -j HEPHAESTUS-RECOVERY$/mu);
	assert.match(ipv4, /^-A HEPHAESTUS-RECOVERY -s 192\.0\.2\.0\/24 -p tcp --dport 22 -j ACCEPT$/mu);
	assert.match(ipv4, /^-A HEPHAESTUS-RECOVERY -j DROP$/mu);
	assert.doesNotMatch(ipv4, /6443|2001:db8/u);
	const ipv6 = recoveryFilter(6, [{ family: 6, source: "2001:db8:1::/64" }], "");
	assert.match(ipv6, /^-A HEPHAESTUS-RECOVERY -p ipv6-icmp --icmpv6-type 135 -j ACCEPT$/mu);
	assert.match(ipv6, /^-A HEPHAESTUS-RECOVERY -s 2001:db8:1::\/64 -p tcp --dport 22 -j ACCEPT$/mu);
});

for (const scenario of [
	"restore",
	"first apply",
	"verification fails",
	"stop fails",
	"schedule rejected",
] as const) {
	void test(`recovery handles ${scenario}`, (t) => {
		const directory = mkdtempSync(path.join(tmpdir(), "host-firewall-recovery-"));
		t.after(() => rmSync(directory, { recursive: true, force: true }));
		const configured = { ...base, k3s: false, http3: false };
		const previous = { ...configured, http3: true };
		const change = { id: "new", policy: configured };
		const policies: string[][] = [];
		const guards = new Set<string>();
		const calls: string[] = [];
		let shouldReject = scenario === "schedule rejected";
		const runtime = {
			directory: path.join(directory, "recovery"),
			pending: path.join(directory, "pending.json"),
			confirmed: path.join(directory, "confirmed.json"),
			defaultsPath: path.join(directory, "defaults"),
			execute(command: string, args: string[], input?: string) {
				if (command === "/usr/bin/systemd-run") {
					assert.deepEqual(args.slice(-5), recoveryCommand(runtime.directory));
					if (shouldReject) {
						throw new Error("schedule rejected");
					}
				} else if (command.endsWith("-restore")) {
					assert.match(input ?? "", /-P INPUT DROP/u);
					guards.add(command.slice(0, -"-restore".length));
				} else if (args.includes("-S")) {
					return guards.has(command)
						? "-P INPUT DROP\n-P FORWARD DROP\n-A INPUT -j HEPHAESTUS-RECOVERY\n-A FORWARD -j HEPHAESTUS-RECOVERY-FORWARD"
						: "";
				} else if (args.includes("-X")) {
					if (args.at(-1) === "HEPHAESTUS-RECOVERY-FORWARD") {
						guards.delete(command);
					}
				} else if (command === "/usr/sbin/ufw" && args.includes("reset")) {
					assert.equal(guards.size, 2);
					policies.push([]);
					calls.push("reset");
				} else if (command === "/usr/sbin/ufw" && args[0] === "allow") {
					policies.at(-1)?.push(args.join(" "));
				} else if (args.includes("stop")) {
					assert.deepEqual(JSON.parse(readFileSync(runtime.confirmed, "utf8")), change);
					calls.push("stop");
					if (args.at(-1)?.endsWith(".service") === true) {
						throw new Error("could not stop service");
					}
				}
				return "";
			},
			drift(rules: ReturnType<typeof rulesFor>, guarded?: boolean) {
				assert.equal(guarded, true);
				assert.equal(guards.size, 2);
				assert.deepEqual(
					policies.at(-1),
					rules.map((rule) => ufwArgs(rule).join(" ")),
				);
				calls.push("verify");
				return scenario === "verification fails" && rules.some((rule) => rule.port === 443)
					? [{ reason: "live_rules" as const, message: "The re-applied rules differ." }]
					: [];
			},
		};
		writeFileSync(runtime.defaultsPath, "IPV6=yes\nMANAGE_BUILTINS=no\n");
		if (scenario !== "first apply") {
			writeFileSync(runtime.confirmed, JSON.stringify({ id: "old", policy: previous }));
		}
		if (scenario === "schedule rejected") {
			assert.throws(() => armRecovery(change, runtime), /schedule rejected/u);
			restoreUfw(runtime);
			assert.deepEqual(calls, []);
			shouldReject = false;
		}
		armRecovery(change, runtime);
		writeFileSync(runtime.pending, JSON.stringify({ ...change, started: true }));
		if (scenario === "stop fails") {
			commitPolicy(change, runtime);
			restoreUfw(runtime);
			assert.deepEqual(calls, ["stop", "stop"]);
			assert.ok(!existsSync(runtime.pending));
		} else if (scenario === "first apply" || scenario === "verification fails") {
			assert.throws(() => restoreUfw(runtime), /applied a VPN-only emergency policy/u);
			assert.deepEqual(calls, [
				...(scenario === "verification fails" ? ["reset", "verify"] : []),
				"reset",
				"verify",
			]);
			assert.deepEqual(policies.at(-1), shared.slice(0, 2));
			assert.equal(guards.size, 2);
		} else {
			restoreUfw(runtime);
			assert.deepEqual(calls, ["reset", "verify"]);
			assert.deepEqual(policies[0], [...shared, "allow 443/udp"]);
			assert.equal(guards.size, 0);
			assert.ok(!existsSync(runtime.pending));
		}
	});
}

const k3sV4 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	'-A INPUT -m comment --comment "KUBE-ROUTER rule to explicitly ACCEPT traffic that comply to network policies" -m mark --mark 0x20000/0x20000 -j ACCEPT',
	"-A INPUT -j ufw-before-input",
	"-A FORWARD -j ufw-before-forward",
	"-A OUTPUT -j ufw-before-output",
	"-A ufw-before-input -j ufw-not-local",
	"-A ufw-before-input -j ufw-user-input",
	"-A ufw-before-forward -j ufw-user-forward",
	"-A ufw-before-output -j ufw-user-output",
	"-A ufw-user-input -s 192.0.2.0/24 -p tcp -m tcp --dport 22 -j ACCEPT",
	"-A ufw-user-input -s 198.51.100.4/32 -p tcp -m tcp --dport 9100 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 80 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 443 -j ACCEPT",
	"-A ufw-user-input -i cni0 -j ACCEPT",
	"-A ufw-user-input -i flannel.1 -j ACCEPT",
	"-A ufw-user-input -s 10.42.0.0/16 -j ACCEPT",
	"-A ufw-user-input -s 10.43.0.0/16 -j ACCEPT",
];
const k3sV6 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	"-A INPUT -i lo -j ACCEPT",
	"-A INPUT -j ufw6-before-input",
	"-A FORWARD -j ufw6-before-forward",
	"-A OUTPUT -j ufw6-before-output",
	"-A ufw6-before-input -m rt --rt-type 0 -j DROP",
	"-A ufw6-before-input -j ufw6-user-input",
	"-A ufw6-before-forward -j ufw6-user-forward",
	"-A ufw6-before-output -j ufw6-user-output",
	"-A ufw6-user-input -s 2001:db8:1::/64 -p tcp -m tcp --dport 22 -j ACCEPT",
	"-A ufw6-user-input -s 2001:db8:2::4/128 -p tcp -m tcp --dport 9100 -j ACCEPT",
	"-A ufw6-user-input -p tcp -m tcp --dport 80 -j ACCEPT",
	"-A ufw6-user-input -p tcp -m tcp --dport 443 -j ACCEPT",
	"-A ufw6-user-input -i cni0 -j ACCEPT",
	"-A ufw6-user-input -i flannel.1 -j ACCEPT",
];

void test("the check reports unmanaged input rules and jumps around the UFW user rules", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	for (const [family, lines, exception] of [
		[4, k3sV4, "-A INPUT -p tcp -m tcp --dport 6443 -j ACCEPT"],
		[4, k3sV4, "-A INPUT -m mark --mark 0x10000/0x10000 -j ACCEPT"],
		[4, k3sV4, '-A INPUT -m comment --comment "local rules" -j CUSTOM-INPUT'],
		[6, k3sV6, "-A ufw6-before-input -p udp -m udp --dport 8472 -j ACCEPT"],
		[6, k3sV6, "-A ufw6-after-input -p udp -m udp --dport 8472 -j ACCEPT"],
	] as const) {
		assert.deepEqual(liveDrift([...lines, exception], family, rules), [
			`IPv${family} has an unmanaged input rule.`,
		]);
	}
	assert.deepEqual(
		liveDrift(
			[...k3sV4, "-A ufw-before-input -p udp -m udp --sport 67 --dport 68 -j ACCEPT"],
			4,
			rules,
		),
		[],
	);
});

void test("the check reports disabled IPv6 even when the other UFW settings match", () => {
	const expected = [
		"DEFAULT_INPUT_POLICY=DROP",
		"DEFAULT_OUTPUT_POLICY=ACCEPT",
		"DEFAULT_FORWARD_POLICY=DROP",
	].join("\n");
	assert.deepEqual(
		settingsDrift(`IPV6=no\n${expected}`, "ENABLED=yes", "enabled").map(({ reason }) => reason),
		["ipv6_disabled"],
	);
	assert.deepEqual(settingsDrift(`IPV6=yes\n${expected}`, "ENABLED=yes", "enabled"), []);
});

void test("recognizes both live k3s rule sets and checks saved filter rules per family", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const saved = [
		"-A ufw-user-input -p tcp --dport 22 -s 192.0.2.0/24 -j ACCEPT",
		"-A ufw-user-input -p tcp --dport 9100 -s 198.51.100.4 -j ACCEPT",
		"-A ufw-user-input -p tcp --dport 80 -j ACCEPT",
		"-A ufw-user-input -p tcp --dport 443 -j ACCEPT",
		"-A ufw-user-input -i cni0 -j ACCEPT",
		"-A ufw-user-input -i flannel.1 -j ACCEPT",
		"-A ufw-user-input -s 10.42.0.0/16 -j ACCEPT",
		"-A ufw-user-input -s 10.43.0.0/16 -j ACCEPT",
	];
	assert.equal(savedDrift(["# UFW metadata", ...saved.toReversed()].join("\n"), 4, rules), false);
	assert.equal(savedDrift(saved.slice(1).join("\n"), 4, rules), true);
	assert.deepEqual(liveDrift(k3sV4, 4, rules), []);
	assert.deepEqual(liveDrift(k3sV6, 6, rules), []);
});

void test("reports denies before required input rules and allows stock invalid-packet drops", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const builtin = "-A INPUT -p tcp -m tcp --dport 443 -j DROP";
	const before = "-A ufw6-before-input -p tcp -m tcp --dport 22 -j REJECT";
	for (const deny of [builtin, before]) {
		assert.deepEqual(liveDrift([deny, ...k3sV6], 6, rules), [
			"IPv6 has an input deny before the required UFW rules.",
		]);
		assert.deepEqual(liveDrift([...k3sV6, deny], 6, rules), []);
	}
	assert.deepEqual(
		liveDrift(["-A ufw6-before-input -m conntrack --ctstate INVALID -j DROP", ...k3sV6], 6, rules),
		[],
	);
});

void test("detects changed defaults, missing live hooks, and extra live rules", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const changed = k3sV6.filter(
		(line) =>
			!line.includes("-P INPUT") &&
			!line.includes("before-forward -j") &&
			!line.includes("-i cni0"),
	);
	assert.deepEqual(
		liveDrift(
			[
				...changed,
				"-P INPUT ACCEPT",
				"-A ufw6-user-output -j DROP",
				"-A ufw6-user-forward -j ACCEPT",
			],
			6,
			rules,
		),
		[
			"IPv6 UFW INPUT chain or policy differs.",
			"IPv6 UFW OUTPUT has extra rules.",
			"IPv6 UFW FORWARD chain or policy differs.",
			"IPv6 UFW FORWARD has extra rules.",
			"IPv6 live UFW input rules differ.",
		],
	);
});

void test("an enabled persistence service cannot share ownership with UFW", () => {
	assert.equal(conflictingSnapshot("enabled", true, false), true);
	assert.equal(conflictingSnapshot("enabled-runtime", false, true), true);
	assert.equal(conflictingSnapshot("enabled", false, false), false);
	assert.equal(conflictingSnapshot("disabled", true, true), false);
	assert.equal(conflictingSnapshot("", false, false), false);
});

void test("reports the verdict, a bounded set of drift reasons, and the check time", () => {
	const metrics = renderPolicyMetrics(["live_rules", "netfilter_persistent", "live_rules"], 123);
	assert.match(metrics, /^host_firewall_policy_ok 0$/mu);
	assert.match(metrics, /^host_firewall_policy_check_timestamp_seconds 123$/mu);
	assert.deepEqual(
		metrics.split("\n").filter((line) => line.startsWith("host_firewall_policy_drift{")),
		[
			'host_firewall_policy_drift{reason="live_rules"} 1',
			'host_firewall_policy_drift{reason="netfilter_persistent"} 1',
		],
	);
	assert.match(renderPolicyMetrics([], 124), /^host_firewall_policy_ok 1$/mu);
	assert.doesNotMatch(renderPolicyMetrics([], 124), /^host_firewall_policy_drift\{/mu);
});
