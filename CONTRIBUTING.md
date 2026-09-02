# Contributing

## Setup

Needs JDK 21 and, for the UI, Node 20. On a machine with only Docker, run
Gradle and npm through the images the CI workflow uses; see "Running from
source" in `README.md` for the exact commands.

```bash
git clone https://github.com/Yasser-Ameur/minigoogle.git
cd minigoogle
./gradlew build -x test
```

## Before opening a pull request

- `./gradlew test` passes. The suite covers the HTTP server, the document
  store, the index, ranking, the semantic path and the Raft cluster.
- If you changed `frontend/`, rebuild the UI (`cd frontend && npm install &&
  npm run build`) and let Gradle's `frontendBuild` task copy the result into
  `src/main/resources/demo/index.html`. CI fails the build when that checked
  in file is stale.
- Update `CHANGELOG.md` under `## [Unreleased]` for any user-visible change.
- If a change affects a claim in `README.md`, update `docs/readme-trace.md`
  with the command or the `path:line` that backs the new claim.

## Style

Match the existing code: no dependency injection framework, `RestServer`
wraps `com.sun.net.httpserver`, and configuration flows through
`ConfigurationLoader` (environment, then `config/application.yaml`, then
built-in defaults). Keep changes scoped to what the pull request is about.

## Reporting bugs

Open a GitHub issue with the endpoint or component involved, the request and
response (or log line) that shows the problem, and the commit or version you
ran. For a security issue, use `SECURITY.md` instead of a public issue.
