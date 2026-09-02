# Security policy

## Supported versions

MiniGoogle is a single-maintainer project with one active line of
development. Security fixes land on `master` and the latest tagged release
only; there are no older release branches to backport to.

## Reporting a vulnerability

Open a private report through GitHub's
[Security Advisories](https://github.com/Yasser-Ameur/minigoogle/security/advisories/new)
for this repository rather than a public issue. Include the affected version
or commit, the endpoint or component involved, and steps to reproduce.

There is no fixed SLA. Expect an acknowledgement within a week and a fix or a
public response once the report is confirmed.

## Scope

Covers the code in this repository: the HTTP server, the crawler, the index
and ranking pipeline, the cluster (gossip and Raft) code, and the bundled
Docker image and Kubernetes manifests under `k8s/`. Third-party dependencies
pulled in by Gradle or npm are out of scope; report those upstream.

## Known operational risks

These are documented behaviour, not vulnerabilities to report:

- The optional API key (`security.apiKey` / `MINIGOGLE_API_KEY`) protects
  `POST /api/v1/crawl` and `/metrics` only when set; a blank key leaves those
  routes open, as described in the Authentication section of `README.md`.
- Internal cluster RPC routes trust any request that reaches them; they are
  meant to sit behind a private network, not be exposed publicly.
