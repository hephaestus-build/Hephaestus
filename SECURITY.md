# Security Policy

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull requests.**

Report privately via [GitHub private vulnerability reporting](https://github.com/hephaestus-build/Hephaestus/security/advisories/new) — it keeps the report confidential and credits you in the resulting advisory. If you cannot use GitHub, email [felixtj.dietrich@tum.de](mailto:felixtj.dietrich@tum.de) with the subject "Hephaestus Security Vulnerability Report".

Please include as much as you can:

- The affected component (application server, webapp, webhook receiver, deployment configuration, ...)
- Steps to reproduce, ideally with a proof of concept
- The impact — what an attacker could achieve
- Any suggested fix or mitigation

## What to Expect

Security reports are triaged before other work.

- **Initial response within 14 days.**
- We follow **coordinated disclosure**: please give us time to ship a fix before disclosing publicly. If we cannot agree on a timeline, we treat **90 days** from your report as the default disclosure date, shortened when a vulnerability is being actively exploited.
- Confirmed vulnerabilities are fixed as soon as feasible and published as [GitHub Security Advisories](https://github.com/hephaestus-build/Hephaestus/security/advisories). You are credited unless you prefer to stay anonymous.

We do not run a bug bounty program.

## Safe Harbor

We consider good-faith security research conducted under this policy to be authorized, and we will not pursue or support legal action against you for it. If a third party takes action against you for such research, we will make our authorization known. In return, only access the minimum data needed to demonstrate an issue, and do not degrade, disrupt, or destroy data or service.

## Supported Versions

Hephaestus is pre-1.0 and released continuously from `main`; **only the latest release is supported**. There are no maintenance branches or backports.

## Scope

In scope: the code in this repository — the Spring Boot application server (including the webhook receiver), the React webapp, and the deployment/Docker configuration we ship.

See the [published threat model](https://docs.hephaestus.build/admin/threat-model) for trust boundaries, attacker assumptions, controls, and residual risks.

Out of scope:

- Vulnerabilities in third-party dependencies without a Hephaestus-specific exploit path — report those upstream
- Denial-of-service, volumetric attacks, and scanner output without a demonstrated impact
- Social engineering or phishing of maintainers or users
- Testing against deployments you do not operate — **do not test against our production instances**; run your own (see the [local development guide](https://docs.hephaestus.build/contributor/local-development))

## Automated Security Measures

Independent of this reporting channel, the project runs:

- **Dependency updates**: [Renovate](https://docs.renovatebot.com/) proposes vulnerability fixes without dashboard approval or a minimum release age ([`renovate.json`](renovate.json))
- **Dependency & secret scanning in CI**: Trivy filesystem scan and TruffleHog ([`ci-security-scan.yml`](.github/workflows/ci-security-scan.yml))
- **Static analysis**: compiler checks and repository-specific Semgrep rules; these do not provide comprehensive interprocedural security analysis
- **Native alerts**: GitHub secret scanning and Dependabot dependency alerts

A machine-readable [`security.txt`](https://hephaestus.build/.well-known/security.txt) ([RFC 9116](https://www.rfc-editor.org/info/rfc9116/)) points to this policy.
