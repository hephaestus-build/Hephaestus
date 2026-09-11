import assert from "node:assert/strict";
import { describe, test } from "node:test";

import {
	findEnvDrift,
	findSeedPolicyGaps,
	findStaleOmissions,
	findViolations,
	REQUIRED_SWITCHES,
} from "./check-preview-stack.ts";

interface Service {
	environment: Record<string, string>;
	security_opt?: string[];
	cap_drop?: string[];
	cap_add?: string[];
	build?: { context: string; dockerfile?: string };
	deploy?: { resources: { limits: { memory: string } } };
	volumes?: { source: string; read_only?: boolean }[];
	privileged?: boolean;
	network_mode?: string;
	ports?: { published: string }[];
}

interface Stack {
	services: {
		appserver: Service;
		postgres: Service;
		webapp?: Service;
		"seed-loader"?: Service;
	};
	networks: Record<string, { internal?: boolean; external?: boolean; name?: string }>;
}

const sandboxed: Stack = {
	services: {
		appserver: {
			environment: {
				...REQUIRED_SWITCHES,
				HEPHAESTUS_TRUSTED_PROXIES: "172.(1[6-9]|2[0-9]|3[01]).[0-9]{1,3}.[0-9]{1,3}",
				WEBHOOK_SECRET: "0123456789012345678901234567890123456789",
			},
			deploy: { resources: { limits: { memory: "2147483648" } } },
			security_opt: ["no-new-privileges:true"],
			cap_drop: ["ALL"],
		},
		postgres: {
			environment: {},
			volumes: [{ source: "postgres-data" }],
			deploy: { resources: { limits: { memory: "536870912" } } },
			security_opt: ["no-new-privileges:true"],
			cap_drop: ["ALL"],
			cap_add: ["CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID"],
		},
	},
	networks: {},
};

/** Clones the good stack, then lets a test break exactly one thing. */
function mutated(change: (stack: Stack) => void): Stack {
	const copy = structuredClone(sandboxed);
	change(copy);
	return copy;
}

void describe("the API path and the OAuth prefix", () => {
	/** A stack whose webapp is served the API at `path` and whose appserver overrides the prefix. */
	function addressed(path: string, override?: string): Stack {
		return mutated((stack) => {
			stack.services.webapp = {
				environment: { APPLICATION_SERVER_URL: `https://pr1.example.com${path}` },
				security_opt: ["no-new-privileges:true"],
				cap_drop: ["ALL"],
				deploy: { resources: { limits: { memory: "1" } } },
			};
			if (override !== undefined) {
				stack.services.appserver.environment.HEPHAESTUS_AUTH_API_BASE_PATH = override;
			}
		});
	}

	// The server normalizes the prefix before it builds a URL with it, so these all mean `/api` and
	// none of them is a mismatch.
	for (const override of ["/api", "api", "/api/", "//api", "  /api  "]) {
		void test(`accepts ${JSON.stringify(override)}, which the server reads as /api`, () => {
			assert.deepEqual(findViolations(addressed("/api", override)), []);
		});
	}

	void test("accepts the profile's own prefix when the stack overrides nothing", () => {
		assert.deepEqual(findViolations(addressed("/api")), []);
	});

	void test("rejects a prefix the API is not served under, which breaks only sign-in", () => {
		const violations = findViolations(addressed("/api", ""));
		assert.equal(violations.length, 1);
		assert.match(violations[0] ?? "", /sign-in would leave the API/);
	});
});

void describe("preview stack sandbox", () => {
	void test("accepts the stack as this repository ships it", () => {
		assert.deepEqual(findViolations(sandboxed), []);
	});

	void test("rejects every documented way out of the sandbox", () => {
		const escapes: [string, (stack: Stack) => void, RegExp][] = [
			[
				"docker socket, which a read-only mount does not make safe",
				(s) => {
					s.services.postgres.volumes = [{ source: "/var/run/docker.sock", read_only: true }];
				},
				/Docker socket/,
			],
			[
				"build stage",
				(s) => {
					s.services.postgres.build = { context: "/repo" };
				},
				/builds from pull-request source/,
			],
			[
				"privileged",
				(s) => {
					s.services.postgres.privileged = true;
				},
				/runs privileged/,
			],
			[
				"host networking",
				(s) => {
					s.services.postgres.network_mode = "host";
				},
				/network_mode/,
			],
			[
				"published port",
				(s) => {
					s.services.postgres.ports = [{ published: "5432" }];
				},
				/publishes a port/,
			],
			[
				"unbounded memory",
				(s) => {
					delete s.services.postgres.deploy;
				},
				/no memory limit/,
			],
			[
				"a project-scoped network every preview would share",
				(s) => {
					s.networks.backend = { internal: true };
				},
				/every preview would share/,
			],
		];

		for (const [label, escape, expected] of escapes) {
			const violations = findViolations(mutated(escape));
			assert.equal(violations.length, 1, `${label}: ${violations.join("; ")}`);
			assert.match(violations[0] ?? "", expected, label);
		}
	});

	void test("catches hardening dropped from a service", () => {
		for (const [label, escape, expected] of [
			[
				"privileges",
				(stack: Stack) => {
					stack.services.postgres.security_opt = [];
				},
				/does not set no-new-privileges/,
			],
			[
				"capabilities",
				(stack: Stack) => {
					stack.services.webapp = {
						environment: {},
						deploy: { resources: { limits: { memory: "1" } } },
					};
				},
				/does not drop all capabilities/,
			],
			[
				"an unrecorded capability",
				(stack: Stack) => {
					stack.services.postgres.cap_add = ["SYS_ADMIN"];
				},
				/not recorded here/,
			],
		] as [string, (stack: Stack) => void, RegExp][]) {
			const violations = findViolations(mutated(escape));
			assert.ok(
				violations.some((violation) => expected.test(violation)),
				`${label}: ${violations.join("; ")}`,
			);
		}
	});

	void test("accepts an external network, which names one that already exists", () => {
		const violations = findViolations(
			mutated((s) => {
				s.networks["staging-shared"] = { external: true, name: "shared-network" };
			}),
		);

		assert.deepEqual(violations, []);
	});

	void test("catches a flipped integration switch", () => {
		const violations = findViolations(
			mutated((s) => {
				s.services.appserver.environment.AGENT_ENABLED = "true";
			}),
		);

		assert.deepEqual(violations, ["appserver sets AGENT_ENABLED=true, expected false"]);
	});

	void test("catches a renamed integration switch, which Spring would silently default to on", () => {
		const violations = findViolations(
			mutated((s) => {
				delete s.services.appserver.environment.MONITORING_RUN_ON_STARTUP;
			}),
		);

		assert.deepEqual(violations, [
			"appserver sets MONITORING_RUN_ON_STARTUP=«unset», expected false",
		]);
	});

	void test("catches a secret the server refuses to start without", () => {
		const violations = findViolations(
			mutated((s) => {
				s.services.appserver.environment.HEPHAESTUS_TRUSTED_PROXIES = "";
			}),
		);

		assert.match(violations[0] ?? "", /empty HEPHAESTUS_TRUSTED_PROXIES/);
	});

	void test("refuses output that is not a rendered stack", () => {
		assert.deepEqual(findViolations(null), ["the rendered stack is not an object"]);
		assert.deepEqual(findViolations({}), [
			"the rendered stack declares no services",
			"the rendered stack has no appserver service, so no switch was checked",
		]);
	});
});

void describe("preview drift from the reference stack", () => {
	const reference = (extra: string) =>
		[
			"services:",
			"  application-server:",
			"    environment:",
			"      DATABASE_URL: postgres",
			extra,
		].join("\n");
	const preview = [
		"services:",
		"  appserver:",
		"    environment:",
		"      DATABASE_URL: postgres",
	].join("\n");

	void test("passes when the preview restates everything the reference sets", () => {
		assert.deepEqual(findEnvDrift(reference(""), preview), []);
	});

	void test("names a reference variable the preview neither sets nor records", () => {
		const drift = findEnvDrift(reference("      BRAND_NEW_SWITCH: true"), preview);

		assert.equal(drift.length, 1);
		assert.match(drift[0] ?? "", /BRAND_NEW_SWITCH/);
	});

	void test("stays quiet for a variable recorded as deliberately omitted", () => {
		assert.deepEqual(findEnvDrift(reference("      SANDBOX_CPUS: 2"), preview), []);
	});
});

void describe("preview seed policy", () => {
	const seedLoader = (policy: string, counted: string[]) =>
		[
			"        psql -h target -v ON_ERROR_STOP=1 <<'SQL'",
			policy,
			"        SQL",
			"",
			'        LIVE=$$(psql -h target -tAX -v ON_ERROR_STOP=1 -c "',
			`          SELECT ${counted.map((table) => `(SELECT count(*) FROM ${table})`).join(" + ")}")`,
			'        if [ "$$LIVE" != "0" ]; then exit 1; fi',
		].join("\n");

	void test("passes when the policy clears exactly what the verification counts", () => {
		const stack = seedLoader(
			"        UPDATE llm_connection SET api_key = NULL;\n        DELETE FROM issued_jwt;",
			["llm_connection", "issued_jwt"],
		);

		assert.deepEqual(findSeedPolicyGaps(stack), []);
	});

	void test("names a credential store the policy clears and nothing counts", () => {
		const stack = seedLoader(
			"        UPDATE llm_connection SET api_key = NULL;\n        UPDATE workspace_llm_connection SET api_key = NULL;",
			["llm_connection"],
		);

		assert.deepEqual(findSeedPolicyGaps(stack), [
			"the policy changes workspace_llm_connection but the verification query never counts it",
		]);
	});

	void test("names a store the verification counts and the policy never clears", () => {
		const stack = seedLoader("        UPDATE llm_connection SET api_key = NULL;", [
			"llm_connection",
			"connection",
		]);

		assert.deepEqual(findSeedPolicyGaps(stack), [
			"the verification query counts connection but the policy never changes it",
		]);
	});

	void test("refuses a seed loader that applies or verifies nothing", () => {
		assert.deepEqual(findSeedPolicyGaps("services:\n  seed-loader:\n"), [
			"the seed loader applies no SQL policy",
		]);
		assert.deepEqual(
			findSeedPolicyGaps("        psql <<'SQL'\n        DELETE FROM issued_jwt;\n        SQL\n"),
			["the seed loader marks a clone seeded without verifying its policy"],
		);
	});
});

void test("flags an omission entry whose reference variable no longer exists", () => {
	const stale = findStaleOmissions(
		"services:\n  application-server:\n    environment:\n      KEEP: 1",
	);

	assert.ok(stale.length > 0);
	assert.match(stale[0] ?? "", /no longer sets it/);
});
