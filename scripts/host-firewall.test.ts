import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	applyAction,
	armRecovery,
	conflictingSnapshot,
	filesDigest,
	liveDrift,
	managedLiveState,
	recoveryCommand,
	recoveryFilter,
	renderPolicyMetrics,
	requireFreshSshConnection,
	restoreSnapshot,
	restoreUfw,
	ruleChanges,
	rulesFor,
	savedRules,
	settingsDrift,
	sshStartedAt,
	ufwArgs,
	verifyRestoredPolicy,
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
	assert.deepEqual(recoveryCommand("/run/firewall-test-snapshot"), [
		"/usr/bin/flock",
		"/run/hephaestus-host-firewall.lock",
		process.execPath,
		path.join("/run/firewall-test-snapshot", "host-firewall.ts"),
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

void test("inactive recovery ignores empty UFW chains but checks effective filtering", () => {
	const defaults = "-P INPUT ACCEPT\n-P FORWARD ACCEPT\n-P OUTPUT ACCEPT";
	const disabled = `${defaults}\n-N ufw-before-input\n-A INPUT -j ufw-before-input`;
	assert.deepEqual(
		managedLiveState(false, () => defaults),
		managedLiveState(false, () => disabled),
	);
	assert.notDeepEqual(
		managedLiveState(false, () => defaults),
		managedLiveState(false, () => `${disabled}\n-A ufw-before-input -j DROP`),
	);
	assert.notDeepEqual(
		managedLiveState(true, () => defaults),
		managedLiveState(true, () => disabled),
	);
});

for (const scenario of ["active", "inactive", "missing", "mismatch"] as const) {
	void test(`the recovery timer follows the ${scenario} rollback sequence`, (t) => {
		const directory = mkdtempSync(path.join(tmpdir(), "host-firewall-timer-"));
		t.after(() => rmSync(directory, { recursive: true, force: true }));
		const files = {
			defaults: path.join(directory, "defaults"),
			"user.rules": path.join(directory, "user.rules"),
			"user6.rules": path.join(directory, "user6.rules"),
			"ufw.conf": path.join(directory, "ufw.conf"),
		};
		const recovery = path.join(directory, "recovery");
		const initial = scenario === "inactive" ? "no" : "yes";
		writeFileSync(files.defaults, "MANAGE_BUILTINS=no\n");
		writeFileSync(files["user.rules"], "previous IPv4\n");
		writeFileSync(files["user6.rules"], "previous IPv6\n");
		writeFileSync(files["ufw.conf"], `ENABLED=${initial}\n`);
		const pristine = "-P INPUT ACCEPT\n-P FORWARD ACCEPT\n-P OUTPUT ACCEPT";
		const protectedDefaults = "-P INPUT DROP\n-P FORWARD DROP\n-P OUTPUT ACCEPT";
		const disabled = `${pristine}\n-N ufw-before-input\n-A INPUT -j ufw-before-input`;
		let liveRules = initial === "yes" ? protectedDefaults : disabled;
		const guardHooks = new Set<string>();
		const calls: string[] = [];
		const configured = rulesFor({ ...base, k3s: false, http3: false });
		const vpn = configured.filter(({ port }) => port === 22);
		const runtime = {
			directory: recovery,
			pending: path.join(directory, "pending"),
			files,
			execute(command: string, args: string[], input?: string) {
				if (command === "/usr/bin/systemd-run") {
					assert.ok(existsSync(path.join(recovery, "snapshot", "user6.rules")));
					assert.deepEqual(args.slice(-5), recoveryCommand(recovery));
					calls.push("schedule");
				} else if (command === "/usr/bin/systemctl") {
					return "enabled";
				} else if (command === "/usr/sbin/ufw" && args.includes("reload")) {
					assert.equal(readFileSync(files["user.rules"], "utf8"), "previous IPv4\n");
					liveRules = protectedDefaults;
					calls.push("reload");
					if (scenario === "mismatch") {
						writeFileSync(files["user6.rules"], "changed during reload\n");
					}
				} else if (command === "/usr/sbin/ufw" && args.includes("disable")) {
					assert.equal(guardHooks.size, 4);
					liveRules = disabled;
					calls.push("disable");
				} else if (command.endsWith("-restore")) {
					assert.match(input ?? "", /-P INPUT DROP/u);
					const binary = command.slice(0, -"-restore".length);
					guardHooks.add(`${binary}:INPUT`);
					guardHooks.add(`${binary}:FORWARD`);
					liveRules = protectedDefaults;
					calls.push("guard");
				} else if (args.includes("-S")) {
					return [
						liveRules,
						...(guardHooks.has(`${command}:INPUT`) ? ["-A INPUT -j HEPHAESTUS-RECOVERY"] : []),
						...(guardHooks.has(`${command}:FORWARD`)
							? ["-A FORWARD -j HEPHAESTUS-RECOVERY-FORWARD"]
							: []),
					].join("\n");
				} else if (args.includes("-D")) {
					guardHooks.delete(`${command}:${args[3] ?? ""}`);
					calls.push("remove");
				}
				return "";
			},
			writePolicy(rules: typeof configured) {
				assert.deepEqual(rules, vpn);
				assert.equal(guardHooks.size, 4);
				calls.push("persist");
			},
			drift() {
				assert.equal(guardHooks.size, 4);
				calls.push("verify emergency");
				return [];
			},
		};
		armRecovery(configured, runtime);
		// A rule changed before the update stopped.
		writeFileSync(files["user.rules"], "partial update\n");
		liveRules += "\n-A ufw-user-input -p tcp --dport 443 -j ACCEPT";
		writeFileSync(runtime.pending, "123");
		if (scenario === "missing" || scenario === "mismatch") {
			if (scenario === "missing") {
				rmSync(path.join(recovery, "snapshot"), { recursive: true });
			}
			assert.throws(() => restoreUfw(runtime), /applied an emergency policy/u);
			assert.deepEqual(calls, [
				"schedule",
				...(scenario === "mismatch" ? ["reload"] : []),
				"guard",
				"guard",
				"persist",
				"guard",
				"guard",
				"verify emergency",
			]);
			assert.ok(existsSync(recovery));
		} else {
			restoreUfw(runtime);
			assert.deepEqual(
				calls,
				scenario === "active"
					? ["schedule", "reload"]
					: ["schedule", "guard", "guard", "disable", "remove", "remove", "remove", "remove"],
			);
			assert.equal(guardHooks.size, 0);
			assert.ok(!existsSync(recovery));
			assert.ok(!existsSync(runtime.pending));
		}
	});
}

for (const enabled of ["yes", "no"]) {
	void test(`recovery restores the previous files after a partial update with ENABLED=${enabled}`, (t) => {
		const directory = mkdtempSync(path.join(tmpdir(), "host-firewall-recovery-"));
		t.after(() => rmSync(directory, { recursive: true, force: true }));
		const snapshot = path.join(directory, "snapshot");
		mkdirSync(snapshot);
		const files = {
			defaults: path.join(directory, "defaults"),
			"user.rules": path.join(directory, "user.rules"),
			"user6.rules": path.join(directory, "user6.rules"),
			"ufw.conf": path.join(directory, "ufw.conf"),
		};
		const expected = {
			defaults: "IPV6=no\n",
			"user.rules": "previous IPv4 rules\n",
			"user6.rules": "previous IPv6 rules\n",
			"ufw.conf": `ENABLED=${enabled}\n`,
		};
		for (const [name, bytes] of Object.entries(expected)) {
			writeFileSync(path.join(snapshot, name), bytes);
			writeFileSync(path.join(directory, name), bytes);
		}
		writeFileSync(
			path.join(snapshot, "ufw.service.state"),
			enabled === "yes" ? "enabled" : "disabled",
		);
		const boot = enabled === "yes" ? "enabled" : "disabled";
		const policy = enabled === "yes" ? "-P INPUT DROP" : "-P INPUT ACCEPT";
		const expectedFiles = filesDigest(files);
		const expectedLive = JSON.stringify([boot, managedLiveState(enabled === "yes", () => policy)]);
		const readRestored = (command: string) => (command === "/usr/bin/systemctl" ? boot : policy);
		assert.throws(() => {
			writeFileSync(files.defaults, "IPV6=yes\n");
			writeFileSync(files["user.rules"], "changed IPv4 rules\n");
			throw new Error("Could not change the IPv6 rules");
		});

		const assertRestored = () => {
			for (const [name, bytes] of Object.entries(expected)) {
				assert.equal(readFileSync(path.join(directory, name), "utf8"), bytes);
			}
		};
		const commands: string[][] = [];
		restoreSnapshot(snapshot, files, (command, args) => {
			commands.push([command, ...args]);
			if (args.includes("reload")) {
				assertRestored();
			}
			return "";
		});
		assertRestored();
		assert.doesNotThrow(() =>
			verifyRestoredPolicy(expectedFiles, expectedLive, files, readRestored),
		);
		assert.throws(
			() =>
				verifyRestoredPolicy(expectedFiles, expectedLive, files, (command) =>
					command === "/usr/bin/systemctl" ? boot : "-P INPUT MISMATCH",
				),
			/differs from the saved snapshot/u,
		);
		assert.deepEqual(
			commands,
			enabled === "yes"
				? [["/usr/sbin/ufw", "reload"]]
				: [
						["/usr/sbin/ufw", "--force", "disable"],
						["/usr/bin/systemctl", "disable", "ufw.service"],
					],
		);
		writeFileSync(files["user6.rules"], "changed after reload\n");
		assert.throws(
			() => verifyRestoredPolicy(expectedFiles, expectedLive, files, readRestored),
			/differs from the saved snapshot/u,
		);
	});
}

function ufwFile(tuples: string[]) {
	return [
		"### RULES ###",
		...tuples.map((tuple) => `### tuple ### ${tuple}`),
		"### END RULES ###",
	].join("\n");
}

const ipv4 = [
	"allow tcp 22 0.0.0.0/0 any 192.0.2.0/24 in comment=56504e",
	"allow tcp 9100 0.0.0.0/0 any 198.51.100.4 in",
	"allow tcp 80 0.0.0.0/0 any 0.0.0.0/0 in",
	"allow tcp 443 0.0.0.0/0 any 0.0.0.0/0 in",
];
const ipv6 = [
	"allow tcp 22 ::/0 any 2001:db8:1::/64 in",
	"allow tcp 9100 ::/0 any 2001:db8:2::4 in",
	"allow tcp 80 ::/0 any ::/0 in",
	"allow tcp 443 ::/0 any ::/0 in",
];

void test("keeps matching saved rules, independent of order and comments", () => {
	const rules = rulesFor({ ...base, k3s: false, http3: false });
	assert.deepEqual(
		ruleChanges(rules, {
			4: savedRules(ufwFile(ipv4.toReversed()), 4),
			6: savedRules(ufwFile(ipv6), 6),
		}),
		{ add: [], remove: [] },
	);
});

void test("repairs a missing IPv6 rule and removes extra IPv4, IPv6, and routed rules by number", () => {
	const rules = rulesFor({ ...base, k3s: false, http3: false });
	const changes = ruleChanges(rules, {
		4: savedRules(ufwFile([...ipv4, "allow tcp 22 0.0.0.0/0 any 0.0.0.0/0 in"]), 4),
		6: savedRules(
			ufwFile([
				...ipv6.slice(0, -1),
				"allow udp 8472 ::/0 any ::/0 in",
				"route:allow any any ::/0 any ::/0 in_eth0!out_eth1",
			]),
			6,
		),
	});
	assert.deepEqual(changes.add.map(ufwArgs), [["allow", "443/tcp"]]);
	assert.deepEqual(changes.remove, [10, 9, 5]);
});

// Managed rules from Ubuntu UFW, with the private source addresses replaced.
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

void test("recognizes both live k3s rule sets and the saved interface tuples", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const tuples4 = [
		...ipv4,
		"allow any any 0.0.0.0/0 any 0.0.0.0/0 in_cni0",
		"allow any any 0.0.0.0/0 any 0.0.0.0/0 in_flannel.1",
		"allow any any 0.0.0.0/0 any 10.42.0.0/16 in",
		"allow any any 0.0.0.0/0 any 10.43.0.0/16 in",
	];
	const tuples6 = [
		...ipv6,
		"allow any any ::/0 any ::/0 in_cni0",
		"allow any any ::/0 any ::/0 in_flannel.1",
	];
	assert.deepEqual(
		ruleChanges(rules, {
			4: savedRules(ufwFile(tuples4), 4),
			6: savedRules(ufwFile(tuples6), 6),
		}),
		{ add: [], remove: [] },
	);
	assert.deepEqual(liveDrift(k3sV4, 4, rules), []);
	assert.deepEqual(liveDrift(k3sV6, 6, rules), []);
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
