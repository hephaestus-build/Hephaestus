import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	apply,
	applyAction,
	armRecovery,
	blockedByBuiltInRules,
	bootGuardRules,
	confirm,
	conflictingSnapshot,
	evaluateFirewall,
	liveDrift,
	publicInterface,
	recoveryCommand,
	recoveryFilter,
	renderPolicyMetrics,
	requireFreshSshConnection,
	restoreUfw,
	reversePathDrift,
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
		[...shared, "allow in on cni0", "allow in on flannel.1"],
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
			{ family: 4, source: "192.0.2.0/24", port: 22, protocol: "tcp" },
			{ family: 6, source: "2001:db8:1::/64", port: 22, protocol: "tcp" },
		],
		"-A INPUT -p tcp --dport 6443 -j ACCEPT\n-A INPUT -j HEPHAESTUS-RECOVERY",
	);
	assert.match(ipv4, /^-P INPUT DROP$/mu);
	assert.match(ipv4, /^-P FORWARD DROP$/mu);
	assert.match(ipv4, /^-D INPUT -j HEPHAESTUS-RECOVERY$/mu);
	assert.match(ipv4, /^-I INPUT 1 -j HEPHAESTUS-RECOVERY$/mu);
	assert.match(
		ipv4,
		/^-A HEPHAESTUS-RECOVERY -s 192\.0\.2\.0\/24 -p tcp -m tcp --dport 22 -j ACCEPT$/mu,
	);
	assert.match(ipv4, /^-A HEPHAESTUS-RECOVERY -j DROP$/mu);
	assert.match(ipv4, /--ctstate RELATED,ESTABLISHED --ctdir REPLY -j ACCEPT/u);
	assert.match(ipv4, /--icmp-type 3\/4 -j ACCEPT/u);
	assert.doesNotMatch(ipv4, /6443|2001:db8|--dport (?:80|443)|-i (?:cni0|flannel\.1)/u);
	const ipv6 = recoveryFilter(
		6,
		[{ family: 6, source: "2001:db8:1::/64", port: 22, protocol: "tcp" }],
		"",
	);
	assert.match(ipv6, /^-A HEPHAESTUS-RECOVERY -p ipv6-icmp --icmpv6-type 135 -j ACCEPT$/mu);
	assert.match(
		ipv6,
		/^-A HEPHAESTUS-RECOVERY -s 2001:db8:1::\/64 -p tcp -m tcp --dport 22 -j ACCEPT$/mu,
	);
});

void test("the rebuild guard admits declared traffic and leaves k3s forwarding to its network policies", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	for (const family of [4, 6] as const) {
		const guard = recoveryFilter(family, rules, "");
		const loopbackDrop = guard.indexOf(
			`-A HEPHAESTUS-RECOVERY -d ${family === 4 ? "127.0.0.0/8" : "::1/128"} -j DROP`,
		);
		assert.ok(loopbackDrop > guard.indexOf("-A HEPHAESTUS-RECOVERY -i lo -j ACCEPT"));
		assert.ok(loopbackDrop < guard.indexOf("-A HEPHAESTUS-RECOVERY -i cni0 -j ACCEPT"));
		assert.match(
			guard,
			/^-A HEPHAESTUS-RECOVERY -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT$/mu,
		);
		assert.match(guard, /^-A HEPHAESTUS-RECOVERY -p tcp -m tcp --dport 80 -j ACCEPT$/mu);
		assert.match(guard, /^-A HEPHAESTUS-RECOVERY -p tcp -m tcp --dport 443 -j ACCEPT$/mu);
		assert.match(guard, /^-A HEPHAESTUS-RECOVERY -i cni0 -j ACCEPT$/mu);
		assert.match(guard, /^-A HEPHAESTUS-RECOVERY-FORWARD -i flannel\.1 -j RETURN$/mu);
		assert.match(
			guard,
			/^-A HEPHAESTUS-RECOVERY-FORWARD -p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport 443 -j RETURN$/mu,
		);
		assert.match(guard, /^-A HEPHAESTUS-RECOVERY-FORWARD -j DROP$/mu);
		assert.doesNotMatch(guard, /6443|(?:-p udp ).*443/u);
	}
});

function wanted(rules: ReturnType<typeof rulesFor>) {
	return rules.map((rule) => ufwArgs(rule).join(" "));
}

function guardRules(input: string) {
	return input.split("\n").filter((line) => line.startsWith("-A HEPHAESTUS-RECOVERY"));
}

function assertEmergencyGuards(guards: Map<string, string>, includesConfirmedVpn: boolean) {
	assert.equal(guards.size, 2);
	const ipv4 = guards.get("/usr/sbin/iptables") ?? "";
	const ipv6 = guards.get("/usr/sbin/ip6tables") ?? "";
	assert.match(ipv4, /192\.0\.2\.0\/24 .*--dport 22 -j ACCEPT/u);
	assert.match(ipv6, /2001:db8:1::\/64 .*--dport 22 -j ACCEPT/u);
	if (includesConfirmedVpn) {
		assert.match(ipv4, /198\.51\.100\.128\/25 .*--dport 22 -j ACCEPT/u);
	}
	for (const filter of [ipv4, ipv6]) {
		assert.match(filter, /--ctstate RELATED,ESTABLISHED --ctdir REPLY -j ACCEPT/u);
		assert.doesNotMatch(filter, /--dport (?:80|443) /u);
	}
}

function readTestChange(file: string) {
	const data: unknown = JSON.parse(readFileSync(file, "utf8"));
	assert.ok(
		typeof data === "object" && data !== null && "id" in data && typeof data.id === "string",
	);
	return {
		id: data.id,
		started: "started" in data ? data.started : undefined,
		changedAt: "changedAt" in data ? data.changedAt : undefined,
		policy: "policy" in data ? data.policy : undefined,
	};
}

function testFirewallState(
	rules: ReturnType<typeof rulesFor>,
	bootGuardActive = false,
): Parameters<typeof evaluateFirewall>[0] {
	const filters = (family: 4 | 6, fixture: string[]) => {
		const chain = family === 4 ? "ufw-user-input" : "ufw6-user-input";
		const selected = rules.filter((rule) => rule.family === undefined || rule.family === family);
		const port = ({ protocol, port: number }: (typeof rules)[number], live: boolean) =>
			protocol === undefined
				? ""
				: ` -p ${protocol}${live ? ` -m ${protocol}` : ""} --dport ${String(number)}`;
		const source = ({ source: network }: (typeof rules)[number]) =>
			network === undefined ? "" : ` -s ${network}`;
		const device = ({ device: interfaceName }: (typeof rules)[number]) =>
			interfaceName === undefined ? "" : ` -i ${interfaceName}`;
		return {
			saved: selected
				.map((rule) => `-A ${chain}${device(rule)}${port(rule, false)}${source(rule)} -j ACCEPT`)
				.join("\n"),
			live: [
				...fixture.filter((line) => !line.startsWith(`-A ${chain} `)),
				...selected.map(
					(rule) => `-A ${chain}${source(rule)}${device(rule)}${port(rule, true)} -j ACCEPT`,
				),
			],
		};
	};
	return {
		defaultSettings:
			"IPV6=yes\nDEFAULT_INPUT_POLICY=DROP\nDEFAULT_OUTPUT_POLICY=ACCEPT\nDEFAULT_FORWARD_POLICY=DROP",
		ufwConfig: "ENABLED=yes",
		ufwUnit: "enabled",
		persistenceProblem: null,
		bootGuardActive,
		reversePath: { all: "0", device: "1" },
		filters: { 4: filters(4, k3sV4), 6: filters(6, k3sV6) },
	};
}

function rejectMutation(): never {
	assert.fail("Apply attempted a mutation.");
}

void test("apply refuses non-strict reverse-path settings before arming", () => {
	const config = { ...base, k3s: true, http3: false };
	const runtime = {
		directory: "/unused",
		pending: "/unused/pending",
		confirmed: "/unused/confirmed",
		config: "/unused/config",
		bootGuard: "/unused/boot-guard",
		defaultsPath: "/unused/defaults",
		execute: rejectMutation,
		drift: rejectMutation,
		unitState: rejectMutation,
		snapshotDrift: () => null,
		sshConnection: () => 90,
		uptime: () => 100,
		readReversePath: () => ({ all: "2", device: "1" }),
	};
	assert.throws(
		() => apply(config, rulesFor(config), runtime),
		/Set strict IPv4 reverse-path filtering/u,
	);
});

for (const scenario of [
	"restore",
	"first apply",
	"verification fails",
	"loose during restore",
	"snapshot enabled during restore",
	"missing config on restore",
	"service unloaded",
	"stop fails",
	"schedule rejected",
	"partial apply",
	"timer expires",
	"late confirm",
	"baseline matches",
	"backup collision",
	"unreadable pending",
	"unreadable first pending",
	"unreadable without VPN",
	"unowned INPUT",
	"unowned FORWARD",
	"unstarted change",
] as const) {
	void test(`recovery handles ${scenario}`, (t) => {
		const directory = mkdtempSync(path.join(tmpdir(), "host-firewall-recovery-"));
		t.after(() => rmSync(directory, { recursive: true, force: true }));
		const configured = { ...base, k3s: false, http3: false };
		const previous = {
			...configured,
			http3: true,
			publicInterface: "eth0",
			sshVpnCidrs: ["198.51.100.128/25", base.sshVpnCidrs[1]],
		};
		const change = { id: "new", policy: configured };
		const policies: string[][] = [];
		const guards = new Map<string, string>();
		const calls: string[] = [];
		let shouldReject = scenario === "schedule rejected";
		let shouldFail = scenario === "partial apply";
		let collision = scenario === "backup collision";
		let waiting = false;
		let bootLoads = 0;
		let bootFilterLoaded = false;
		let armed = 0;
		let sshAge = 90;
		const foreignRule =
			new Map([
				["unowned INPUT", "-A INPUT -s 203.0.113.9/32 -j ACCEPT"],
				["unowned FORWARD", "-A FORWARD -j DROP"],
			]).get(scenario) ?? "";
		const reset = () => {
			assert.equal(guards.size, 2);
			assert.equal(readTestChange(runtime.pending).started, true);
			assert.ok(existsSync(runtime.bootGuard));
			if (collision) {
				collision = false;
				throw new Error("ERROR: '/etc/ufw/user.rules.20261124_153002' already exists. Aborting");
			}
			policies.push([]);
			calls.push("reset");
		};
		const stop = (unit: string | undefined) => {
			assert.equal(readTestChange(runtime.confirmed).id, readTestChange(runtime.pending).id);
			calls.push("stop");
			if (
				unit?.endsWith(".service") === true &&
				["stop fails", "service unloaded"].includes(scenario)
			) {
				throw new Error("could not stop service");
			}
		};
		const bootFilterAction = (target: string | undefined) => {
			bootFilterLoaded = target === runtime.bootGuard;
			if (bootFilterLoaded) {
				assert.match(
					readFileSync(runtime.bootGuard, "utf8"),
					/hook input priority -5; policy drop;/u,
				);
				bootLoads += 1;
			}
		};
		const verifyUnreadableRecovery = () => {
			writeFileSync(runtime.pending, "Unreadable declaration");
			if (scenario === "unreadable without VPN") {
				assert.throws(() => restoreUfw(runtime), /Cannot recover SSH without a valid VPN/u);
				assert.equal(guards.size, 0);
				assert.equal(bootLoads, 0);
				return;
			}
			assert.throws(() => restoreUfw(runtime), /emergency guards are active/u);
			assert.equal(guards.size, 2);
			assert.equal(bootLoads, 1);
			assert.match(
				guards.get("/usr/sbin/iptables") ?? "",
				/192\.0\.2\.0\/24 .*--dport 22 -j ACCEPT/u,
			);
		};
		const runtime = {
			directory: path.join(directory, "recovery"),
			pending: path.join(directory, "pending.json"),
			confirmed: path.join(directory, "confirmed.json"),
			config: path.join(directory, "config.json"),
			bootGuard: path.join(directory, "boot.nft"),
			defaultsPath: path.join(directory, "defaults"),
			snapshotDrift: () => null,
			readReversePath: () => ({ all: "0", device: "1" }),
			uptime: () => 100,
			sshConnection: () => sshAge,
			unitState(_unit: string, property: string) {
				if (property === "SubState") {
					return waiting ? "waiting" : "dead";
				}
				if (property === "LoadState") {
					return scenario === "service unloaded" ? "not-found" : "loaded";
				}
				if (property === "UnitFileState") {
					return "enabled";
				}
				return "inactive";
			},
			execute(command: string, args: string[], input?: string) {
				if (command === "/usr/bin/systemd-run") {
					assert.deepEqual(args.slice(-5), recoveryCommand(runtime.directory));
					if (shouldReject) {
						throw new Error("schedule rejected");
					}
					armed += 1;
					waiting = true;
				} else if (command.endsWith("-restore")) {
					assert.ok(typeof input === "string" && input.includes("-P INPUT DROP"));
					guards.set(command.slice(0, -"-restore".length), input);
				} else if (args.includes("-S")) {
					return guards.has(command)
						? "-P INPUT DROP\n-P FORWARD DROP\n-A INPUT -j HEPHAESTUS-RECOVERY\n-A FORWARD -j HEPHAESTUS-RECOVERY-FORWARD"
						: foreignRule;
				} else if (args.includes("-X")) {
					if (args.at(-1) === "HEPHAESTUS-RECOVERY-FORWARD") {
						guards.delete(command);
					}
				} else if (command === "/usr/sbin/ufw" && args.includes("reset")) {
					reset();
				} else if (command === "/usr/bin/sleep") {
					assert.deepEqual(args, ["1"]);
					calls.push("wait for backup filename");
				} else if (command === "/usr/sbin/ufw" && args[0] === "allow") {
					if (shouldFail && args[1] === "443/tcp") {
						shouldFail = false;
						throw new Error("injected partial apply failure");
					}
					policies.at(-1)?.push(args.join(" "));
				} else if (args.includes("stop")) {
					stop(args.at(-1));
				} else if (command === "/usr/sbin/nft" && args[0] === "--file") {
					bootFilterAction(args.at(-1));
				}
				return "";
			},
			drift(rules: ReturnType<typeof rulesFor>, guarded?: boolean, policy?: unknown) {
				if (scenario === "missing config on restore") {
					assert.deepEqual(policy, previous);
				}
				if (guarded !== true) {
					if (policies.length > 0) {
						assert.equal(guards.size, 0);
						calls.push("verify live");
					}
					const matchesRules =
						scenario === "baseline matches" ||
						JSON.stringify(policies.at(-1)) === JSON.stringify(wanted(rules));
					return evaluateFirewall(
						testFirewallState(matchesRules ? rules : [], bootFilterLoaded),
						rules,
					);
				}
				assert.equal(guards.size, 2);
				assert.deepEqual(
					guardRules(guards.get("/usr/sbin/iptables") ?? ""),
					guardRules(recoveryFilter(4, rules, "")),
				);
				assert.deepEqual(
					guardRules(guards.get("/usr/sbin/ip6tables") ?? ""),
					guardRules(recoveryFilter(6, rules, "")),
				);
				assert.deepEqual(policies.at(-1), wanted(rules));
				calls.push("verify");
				if (scenario === "timer expires") {
					waiting = false;
				}
				const state = testFirewallState(rules, bootFilterLoaded);
				if (scenario === "loose during restore") {
					state.reversePath = { all: "2", device: "1" };
				}
				if (scenario === "snapshot enabled during restore") {
					state.persistenceProblem = "An enabled service restores a saved snapshot.";
				}
				if (scenario === "verification fails" && rules.some((rule) => rule.port === 443)) {
					state.filters[4].live.push("-A ufw-user-input -j DROP");
				}
				return evaluateFirewall(state, rules, true);
			},
		};
		writeFileSync(runtime.defaultsPath, "IPV6=yes\nMANAGE_BUILTINS=no\n");
		if (!["unreadable without VPN", "missing config on restore"].includes(scenario)) {
			writeFileSync(runtime.config, JSON.stringify(configured));
		}
		if (
			![
				"first apply",
				"baseline matches",
				"unreadable first pending",
				"unreadable without VPN",
			].includes(scenario)
		) {
			writeFileSync(runtime.confirmed, JSON.stringify({ id: "old", policy: previous }));
		}
		const desired = rulesFor(configured);
		if (foreignRule !== "") {
			const fixture = testFirewallState(desired);
			fixture.filters[4].live.unshift(foreignRule);
			assert.ok(
				evaluateFirewall(fixture, desired).some(
					(problem) => problem.reason === "live_rules" && problem.message.includes("unmanaged"),
				),
			);
			assert.throws(() => apply(configured, desired, runtime), {
				message: `IPv4 built-in rules survive UFW reset. Review and remove the conflicting rules first.\n${foreignRule}`,
			});
			assert.equal(armed, 0);
			assert.deepEqual(calls, []);
			assert.ok(!existsSync(runtime.pending));
			assert.ok(!existsSync(runtime.bootGuard));
			return;
		}
		if (scenario === "baseline matches") {
			apply(configured, desired, runtime);
			assert.deepEqual(readTestChange(runtime.confirmed).policy, configured);
			assert.equal(armed, 0);
			return;
		}
		if (scenario === "timer expires") {
			assert.throws(() => apply(configured, desired, runtime), /timer has expired/u);
			const state = readTestChange(runtime.pending);
			assert.equal(state.started, true);
			assert.equal(state.changedAt, undefined);
			assert.throws(() => confirm(desired, runtime), /no matching firewall change/u);
			restoreUfw(runtime);
			assert.deepEqual(policies.at(-1), wanted(rulesFor(previous)));
			return;
		}
		if (
			[
				"partial apply",
				"stop fails",
				"service unloaded",
				"schedule rejected",
				"late confirm",
			].includes(scenario)
		) {
			let retryId: string | undefined;
			if (scenario === "schedule rejected") {
				assert.throws(() => apply(configured, desired, runtime), /schedule rejected/u);
				restoreUfw(runtime);
				assert.deepEqual(calls, []);
				shouldReject = false;
			}
			if (scenario === "partial apply") {
				assert.throws(() => apply(configured, desired, runtime), /injected partial apply failure/u);
				const interrupted = readTestChange(runtime.pending);
				assert.equal(interrupted.started, true);
				assert.equal(interrupted.changedAt, undefined);
				retryId = interrupted.id;
				assertEmergencyGuards(guards, true);
				assert.equal(bootLoads, 1);
				assert.match(
					readFileSync(runtime.bootGuard, "utf8"),
					/ip saddr 198\.51\.100\.128\/25 tcp dport 22 accept/u,
				);
			}
			apply(configured, desired, runtime);
			assert.equal(armed, 1);
			const applied = readTestChange(runtime.pending);
			assert.equal(applied.changedAt, 100);
			if (retryId !== undefined) {
				assert.equal(applied.id, retryId);
			}
			assert.throws(() => confirm(desired, runtime), /new SSH connection/u);
			sshAge = 110;
			if (scenario === "late confirm") {
				waiting = false;
				assert.throws(() => confirm(desired, runtime), /after the recovery timer expires/u);
				assert.equal(readTestChange(runtime.confirmed).id, "old");
				restoreUfw(runtime);
				assert.deepEqual(policies.at(-1), wanted(rulesFor(previous)));
				return;
			}
			confirm(desired, runtime);
			assert.equal(existsSync(runtime.pending), scenario === "stop fails");
			assert.deepEqual(policies.at(-1), wanted(desired));
			restoreUfw(runtime);
			assert.ok(!existsSync(runtime.pending));
			assert.ok(!existsSync(runtime.bootGuard));
			return;
		}
		armRecovery(change, runtime);
		if (scenario === "unstarted change") {
			writeFileSync(runtime.bootGuard, bootGuardRules(desired.filter(({ port }) => port === 22)));
			bootFilterLoaded = true;
			restoreUfw(runtime);
			assert.deepEqual(calls, []);
			assert.equal(bootFilterLoaded, false);
			assert.ok(!existsSync(runtime.bootGuard));
			assert.ok(!existsSync(runtime.pending));
			return;
		}
		writeFileSync(runtime.pending, JSON.stringify({ ...change, started: true }));
		if (scenario.startsWith("unreadable")) {
			verifyUnreadableRecovery();
			return;
		}
		writeFileSync(runtime.bootGuard, bootGuardRules(desired.filter(({ port }) => port === 22)));
		if (["first apply", "verification fails"].includes(scenario)) {
			assert.throws(() => restoreUfw(runtime), /applied a VPN-only emergency policy/u);
			assert.deepEqual(calls, [
				...(scenario === "verification fails" ? ["reset", "verify"] : []),
				"reset",
				"verify",
			]);
			assert.deepEqual(policies.at(-1), [
				...shared.slice(0, 2),
				...(scenario === "verification fails"
					? ["allow from 198.51.100.128/25 to any port 22 proto tcp"]
					: []),
			]);
			assertEmergencyGuards(guards, scenario === "verification fails");
			assert.equal(bootFilterLoaded, true);
			assert.match(readFileSync(runtime.bootGuard, "utf8"), /tcp dport 22 accept/u);
		} else if (["loose during restore", "snapshot enabled during restore"].includes(scenario)) {
			assert.throws(
				() => restoreUfw(runtime),
				/Restored the last confirmed UFW policy\. The emergency guard remains active/u,
			);
			assert.deepEqual(calls, ["reset", "verify"]);
			assert.deepEqual(policies, [wanted(rulesFor(previous))]);
			assertEmergencyGuards(guards, true);
			assert.equal(bootFilterLoaded, true);
			assert.ok(existsSync(runtime.pending));
		} else {
			restoreUfw(runtime);
			assert.deepEqual(calls, [
				...(scenario === "backup collision" ? ["wait for backup filename"] : []),
				"reset",
				"verify",
				"verify live",
			]);
			assert.deepEqual(policies[0], wanted(rulesFor(previous)));
			assert.equal(guards.size, 0);
			assert.ok(!existsSync(runtime.pending));
		}
	});
}

const k3sV4 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	'-A INPUT -m comment --comment "kube-router input" -j KUBE-ROUTER-INPUT',
	"-A INPUT -j KUBE-FIREWALL",
	'-A INPUT -m comment --comment "KUBE-ROUTER rule to explicitly ACCEPT traffic that comply to network policies" -m mark --mark 0x20000/0x20000 -j ACCEPT',
	"-A INPUT -j ufw-before-logging-input",
	"-A INPUT -j ufw-before-input",
	"-A INPUT -j ufw-after-input",
	"-A INPUT -j ufw-after-logging-input",
	"-A INPUT -j ufw-reject-input",
	"-A INPUT -j ufw-track-input",
	'-A FORWARD -m comment --comment "kube-router forwarding" -j KUBE-ROUTER-FORWARD',
	"-A FORWARD -m conntrack --ctstate NEW -j KUBE-PROXY-FIREWALL",
	"-A FORWARD -j KUBE-FORWARD",
	"-A FORWARD -j KUBE-SERVICES",
	"-A FORWARD -j KUBE-EXTERNAL-SERVICES",
	"-A FORWARD -j FLANNEL-FWD",
	"-A FORWARD -j DOCKER-USER",
	"-A FORWARD -j DOCKER-FORWARD",
	"-A FORWARD -i br-a0b1c2d3e4f5 ! -o br-a0b1c2d3e4f5 -j ACCEPT",
	"-A FORWARD -j ufw-before-forward",
	"-A OUTPUT -j ufw-before-output",
	"-A ufw-before-input -j ufw-not-local",
	"-A ufw-before-input -j ufw-user-input",
	"-A ufw-before-forward -j ufw-user-forward",
	"-A ufw-before-output -j ufw-user-output",
	'-A ufw-logging-deny -m limit --limit 3/min --limit-burst 10 -j LOG --log-prefix "[UFW BLOCK] "',
	"-A ufw-not-local -m addrtype --dst-type LOCAL -j RETURN",
	"-A ufw-not-local -m addrtype --dst-type MULTICAST -j RETURN",
	"-A ufw-not-local -m addrtype --dst-type BROADCAST -j RETURN",
	"-A ufw-not-local -m limit --limit 3/min --limit-burst 10 -j ufw-logging-deny",
	"-A ufw-not-local -j DROP",
	"-A ufw-skip-to-policy-input -j DROP",
	"-A ufw-user-limit -j REJECT --reject-with icmp-port-unreachable",
	"-A ufw-user-limit-accept -j ACCEPT",
	"-A ufw-user-input -s 192.0.2.0/24 -p tcp -m tcp --dport 22 -j ACCEPT",
	"-A ufw-user-input -s 198.51.100.4/32 -p tcp -m tcp --dport 9100 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 80 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 443 -j ACCEPT",
	"-A ufw-user-input -i cni0 -j ACCEPT",
	"-A ufw-user-input -i flannel.1 -j ACCEPT",
];
const k3sV6 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	"-A INPUT -i lo -j ACCEPT",
	"-A INPUT -j ufw6-before-logging-input",
	"-A INPUT -j ufw6-before-input",
	"-A INPUT -j ufw6-after-input",
	"-A INPUT -j ufw6-after-logging-input",
	"-A INPUT -j ufw6-reject-input",
	"-A INPUT -j ufw6-track-input",
	"-A FORWARD -j KUBE-FORWARD",
	"-A FORWARD -m conntrack --ctstate NEW -j KUBE-PROXY-FIREWALL",
	"-A FORWARD -j ufw6-before-forward",
	"-A OUTPUT -j ufw6-before-output",
	"-A ufw6-before-input -m rt --rt-type 0 -j DROP",
	"-A ufw6-before-input -j ufw6-user-input",
	"-A ufw6-before-forward -j ufw6-user-forward",
	"-A ufw6-before-output -j ufw6-user-output",
	'-A ufw6-logging-deny -m limit --limit 3/min --limit-burst 10 -j LOG --log-prefix "[UFW BLOCK] "',
	"-A ufw6-skip-to-policy-input -j DROP",
	"-A ufw6-user-limit -j REJECT --reject-with icmp6-port-unreachable",
	"-A ufw6-user-limit-accept -j ACCEPT",
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
		[4, k3sV4, "-A ufw-before-logging-input -p tcp -m tcp --dport 6443 -j ACCEPT"],
		[4, k3sV4, "-A ufw-reject-input -p tcp -m tcp --dport 6443 -j ACCEPT"],
		[6, k3sV6, "-A ufw6-track-input -p udp -m udp --dport 8472 -j ACCEPT"],
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

void test("reports altered UFW input helper chains", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	for (const [family, lines, rule] of [
		[4, k3sV4, "-A ufw-not-local -p tcp -m tcp --dport 443 -j DROP"],
		[4, k3sV4, "-A ufw-not-local -j ACCEPT"],
		[4, k3sV4, "-A ufw-logging-deny -j DROP"],
		[4, k3sV4, "-A ufw-skip-to-policy-input -j ACCEPT"],
		[4, k3sV4, "-A ufw-user-limit -j ACCEPT"],
		[6, k3sV6, "-A ufw6-user-limit-accept -p tcp -m tcp --dport 443 -j REJECT"],
	] as const) {
		assert.deepEqual(liveDrift([rule, ...lines], family, rules), [
			`IPv${family} has an unmanaged input rule.`,
		]);
	}
	assert.deepEqual(
		liveDrift(
			k3sV4.filter((line) => !line.includes("--dst-type LOCAL -j RETURN")),
			4,
			rules,
		),
		["IPv4 has an unmanaged input rule."],
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
	];
	assert.equal(savedDrift(["# UFW metadata", ...saved.toReversed()].join("\n"), 4, rules), false);
	assert.equal(savedDrift(saved.slice(1).join("\n"), 4, rules), true);
	assert.deepEqual(liveDrift(k3sV4, 4, rules), []);
	assert.deepEqual(liveDrift(k3sV6, 6, rules), []);
	assert.deepEqual(blockedByBuiltInRules(k3sV4, 4, rules), []);
	assert.deepEqual(blockedByBuiltInRules(k3sV6, 6, rules), []);
	const earlyDeny = "-A INPUT -p tcp -m tcp --dport 443 -j DROP";
	assert.deepEqual(blockedByBuiltInRules([earlyDeny, ...k3sV4], 4, rules), [earlyDeny]);
	for (const network of ["10.42.0.0/16", "10.43.0.0/16"]) {
		const unscoped = `-A ufw-user-input -s ${network} -j ACCEPT`;
		assert.deepEqual(liveDrift([...k3sV4, unscoped], 4, rules), [
			"IPv4 live UFW input rules differ.",
		]);
		assert.equal(savedDrift([...saved, unscoped].join("\n"), 4, rules), true);
	}
});

void test("requires effective strict reverse-path filtering for the public interface", () => {
	assert.equal(
		publicInterface({ publicInterface: "eth0" }, () => ""),
		"eth0",
	);
	assert.equal(
		publicInterface({}, () => "default via 192.0.2.1 dev eth0 proto static"),
		"eth0",
	);
	assert.throws(() => publicInterface({}, () => "default dev eth0\ndefault dev eth1"));
	assert.throws(() => publicInterface({ publicInterface: "../all" }, () => ""));
	assert.throws(() => publicInterface({ publicInterface: "cni0" }, () => ""));

	for (const [global, device] of [
		["2", "1"],
		["0", "2"],
		["0", "0"],
		["1", "2"],
	] as const) {
		assert.deepEqual(
			reversePathDrift(global, device).map(({ reason }) => reason),
			["reverse_path_filter"],
		);
	}
	assert.deepEqual(reversePathDrift("0\n", "1\n"), []);
	assert.deepEqual(reversePathDrift("1", "1"), []);
});

void test("a loaded boot guard reports drift even when UFW matches the policy", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const state = testFirewallState(rules, true);
	const problems = evaluateFirewall(state, rules);
	assert.deepEqual(
		problems.map(({ reason }) => reason),
		["recovery_guard"],
	);
	assert.match(
		renderPolicyMetrics(
			problems.map(({ reason }) => reason),
			1,
		),
		/^host_firewall_policy_ok 0$/mu,
	);
	assert.deepEqual(evaluateFirewall(state, rules, true), []);
	assert.deepEqual(evaluateFirewall({ ...state, bootGuardActive: false }, rules), []);
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
	for (const family of [4, 6] as const) {
		const lines = family === 4 ? k3sV4 : k3sV6;
		const chain = family === 4 ? "ufw-before-logging-input" : "ufw6-before-logging-input";
		assert.deepEqual(
			liveDrift([...lines, `-A ${chain} -p tcp -m tcp --dport 443 -j DROP`], family, rules),
			[`IPv${family} has an input deny before the required UFW rules.`],
		);
		assert.deepEqual(liveDrift([...lines, `-A ${chain} -j RETURN`], family, rules), []);
	}
});

void test("preserves scoped ingress denies for private platform ports", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const denies = [
		'-A INPUT -i eth0 -p udp -m udp --dport 8472 -m comment --comment "protect overlay" -j DROP',
		"-A INPUT -i eth0 -p tcp -m multiport --dports 6443,10250 -j DROP",
	];
	assert.deepEqual(liveDrift([...denies, ...k3sV4], 4, rules), []);
	assert.deepEqual(liveDrift([...denies, ...k3sV6], 6, rules), []);
	for (const conflicting of [
		"-A INPUT -i eth0 -p tcp -m multiport --dports 6443,443 -j DROP",
		"-A INPUT -i eth0 -p tcp -m tcp --dport 9100 -j REJECT",
		"-A INPUT -i cni0 -p tcp -m tcp --dport 6443 -j DROP",
		"-A INPUT -i flannel.1 -p udp -m udp --dport 8472 -j DROP",
	]) {
		assert.deepEqual(liveDrift([conflicting, ...k3sV4], 4, rules), [
			"IPv4 has an input deny before the required UFW rules.",
		]);
	}
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
