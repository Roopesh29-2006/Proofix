# DevBuddy runner — `scan` (Build 2B-0a)

The runner runs on the user's machine and reads their repository locally; the DevBuddy server
never sees code. This first component scans a repository and writes `facts.json`, a
deterministic description of which stacks it contains. It does **not** contact the server,
Gemini or Docker, and it never executes anything from the repository.

```
runner/mvnw package
java -jar runner/target/devbuddy-runner.jar scan <repoPath> [--out facts.json]
        [--max-depth N] [--max-files N] [--max-marker-bytes N]
```

Without `--out`, the JSON goes to stdout. A one-line summary and warning codes go to stderr.

| Exit | Meaning |
|---|---|
| 0 | success (warnings allowed) |
| 2 | usage error |
| 3 | `repoPath` is not a readable directory |
| 4 | output could not be written |
| 1 | unexpected internal failure |

## What `scan` does

1. **List files.**
   - **Primary source:** `git -C <root> ls-files --cached --others --exclude-standard -z`. This respects `.gitignore` and includes untracked, non-ignored files.
   - **Fallback (`fileSource: "walk"`):** a directory walk that never follows links. It is used when the root is not a git repo, git is missing, git times out (10 s), or git fails (for example with "dubious ownership"). The walk raises a `NOT_A_GIT_REPO` or `GIT_UNAVAILABLE` warning.
2. **Check every path, from either source.**
   - Paths must stay inside the root. `..`, absolute paths and drive letters are rejected → `PATH_OUTSIDE_ROOT`.
   - Symlinks, junctions and paths through linked directories are never followed → `SYMLINK_SKIPPED`.
   - A directory listed by git (a submodule) → `SUBMODULE_SKIPPED`.
   - Tracked files that were deleted are dropped silently.
3. **Skip** these directories in both modes, even if they were committed: `.git node_modules target build dist out .next .nuxt vendor .venv venv __pycache__ .gradle .idea .vscode coverage`. They are listed in `scan.dirsSkipped`.
4. **Git info:** `git rev-parse HEAD` and `git status --porcelain` (5 s each). A dirty working tree raises `UNCOMMITTED_CHANGES` and is analyzed as it is. No other git commands are ever run. git runs with `core.fsmonitor=false` and `GIT_OPTIONAL_LOCKS=0`, so it starts no hook programs and takes no locks.
5. **Detect stacks** from marker files (rule table below), confirm them by reading those marker files, and assign roles and a test target.
6. **Flag secret files.** `.env` and `.env.*` (except `.env.example`, `.env.sample`, `.env.template`) raise `SECRET_FILE_PRESENT` with the **path only**.

## What is never read

- **Secret files** (`.env`, `.env.*`): never opened.
- **Contents:** only marker files are read (`package.json`, `pom.xml`, `requirements.txt`, …), at most 2 MB each, without following links. Larger marker files get `sha256: null` and a `FILE_TOO_LARGE` warning.
- **Python entry files** (`main.py`, `manage.py`, …): checked by name only, never read. `setup.py` is read as text and never executed.
- **Output:** no file contents, no git output, and no absolute paths appear in facts.json or in the logs.

## Limits

| Limit | Default | Flag | Warning |
|---|---|---|---|
| directory depth below root | 12 | `--max-depth` | `DEPTH_LIMIT_HIT` |
| files | 20000 (first N by path) | `--max-files` | `FILE_LIMIT_HIT` |
| marker file size | 2 MB | `--max-marker-bytes` | `FILE_TOO_LARGE` |

When a limit is hit, the scan continues with what it has. Hit limits are listed in `scan.limitsHit`.

## Rule table

Within one ecosystem the first matching rule wins (so `nextjs` beats `node`). Rules of different ecosystems in the same directory are **all** kept.

| rule | ecosystem | kind | markers |
|---|---|---|---|
| nextjs | node | nextjs | `package.json` AND `next.config.(js\|mjs\|ts)` |
| node | node | node | `package.json` |
| maven | jvm-maven | java-maven | `pom.xml` |
| gradle | jvm-gradle | java-gradle | `build.gradle(.kts)` \| `settings.gradle(.kts)` |
| python | python | python | `pyproject.toml` \| `requirements.txt` \| `Pipfile` \| `setup.py` |
| go | go | go | `go.mod` |
| ruby | ruby | ruby | `Gemfile` |
| php | php | php | `composer.json` |
| rust | rust | rust | `Cargo.toml` |
| dotnet | dotnet | dotnet | `*.csproj` \| `*.sln` |

**Folding**
- A match folds into the nearest ancestor match of the same ecosystem only if both have the **same kind**. It becomes a member (for example, Maven modules).
- Otherwise it is a separate stack, and `parent` is that ancestor's stack id. With no same-ecosystem ancestor, `parent` is null.

**Confirmation and roles**

| kind | how it is confirmed | role |
|---|---|---|
| node | (`scripts.start` \| `scripts.dev` \| `main`) AND a server dependency (express, fastify, koa, @nestjs/core, @hapi/hapi, hono, @adonisjs/core, sails) | backend |
| node | no server dependency but a frontend dependency (vite, react-scripts, @angular/core, vue, svelte, @sveltejs/kit, nuxt) | frontend |
| node | anything else | tooling (unconfirmed) |
| python | an entry file in the same directory (main.py, app.py, manage.py, wsgi.py, asgi.py), OR a web framework token in the markers (django, flask, fastapi, starlette, sanic, tornado, aiohttp, quart, litestar) | backend |
| python | neither | tooling |
| nextjs | always confirmed | frontend |
| others | the marker alone confirms | backend |

Further role rules:
- An unparseable marker → `confirmed: false`, role tooling, `MARKER_UNPARSEABLE`.
- A too-large marker → `confirmed: false`, `confirmReason: MARKER_TOO_LARGE`.
- **Auxiliary** overrides every other role: it applies when a segment of the stack path is `examples`, `example`, `samples`, `docs`, `test`, `tests`, `fixtures`, `scripts` or `benchmarks`.

**Test target**

| Stacks | status | reason |
|---|---|---|
| exactly one backend | KNOWN | `SINGLE_BACKEND` |
| no backend, exactly one frontend | KNOWN | `ONLY_STACK` |
| more than one backend | UNRESOLVED | `MULTIPLE_BACKENDS` |
| no backend, more than one frontend | UNRESOLVED | `MULTIPLE_FRONTENDS` |
| only tooling or auxiliary stacks | UNRESOLVED | `NO_TESTABLE_STACK` |
| no stacks at all | UNRESOLVED | `NO_STACK_DETECTED` |

Tooling and auxiliary stacks are never targets. When there are no stacks but there is a `Dockerfile`, the scan raises `DOCKERFILE_ONLY`.

## facts.json (schemaVersion 1)

The file is deterministic: fixed key order, 2-space indent, LF line endings, UTF-8, no timestamps, no absolute paths. The same tree gives byte-identical output.

```json
{
  "schemaVersion": 1,
  "repo": { "commitSha": "40-hex | null", "dirty": false, "fileSource": "git | walk" },
  "scan": { "filesSeen": 11, "dirsSkipped": ["node_modules", "target"], "limitsHit": [] },
  "stacks": [
    {
      "id": "java-maven@.",
      "kind": "java-maven",
      "path": ".",
      "role": "backend | frontend | tooling | auxiliary",
      "parent": "stack id | null",
      "members": ["api", "common"],
      "evidence": {
        "rule": "maven",
        "confirmed": true,
        "confirmReason": "marker pom.xml",
        "matched": [ { "path": "pom.xml", "sha256": "hex | null", "size": 310 } ]
      }
    }
  ],
  "testTarget": { "stackId": "java-maven@. | null", "status": "KNOWN | UNRESOLVED", "reason": "SINGLE_BACKEND" },
  "facts": [],
  "warnings": [ { "code": "SECRET_FILE_PRESENT", "path": ".env", "message": "..." } ]
}
```

- **Ordering:**
  - Stack `id` is `kind@path`, where `.` is the repository root.
  - Stacks are sorted root first, then by path.
  - `matched` includes the marker files of all members and is sorted by path.
  - Warnings are sorted by code, then path.
- **`facts`** is always empty in 2B-0a. Its element shape, `{name, value, status, label, source, evidence: [{path, line}]}`, is reserved for 2B-0b.

**Warning codes:**
- git: `GIT_UNAVAILABLE`, `NOT_A_GIT_REPO`, `UNCOMMITTED_CHANGES`
- paths: `PATH_OUTSIDE_ROOT`, `SYMLINK_SKIPPED`, `SUBMODULE_SKIPPED`, `SPECIAL_FILE_SKIPPED`, `PATH_UNREADABLE`
- limits: `DEPTH_LIMIT_HIT`, `FILE_LIMIT_HIT`, `FILE_TOO_LARGE`
- detection and secrets: `MARKER_UNPARSEABLE`, `DOCKERFILE_ONLY`, `SECRET_FILE_PRESENT`

## Tests

`runner/mvnw test`. Fixtures live in `src/test/resources/fixtures/`. Names that the repository's `.gitignore` hides are stored as `__node_modules` and `__target` and renamed when a fixture is loaded. No `.env` is committed: tests write one containing a random canary and check that it never appears in output. The real-symlink tests are skipped (and reported as skipped) on Windows machines that can't create symlinks. Junction tests cover the same code paths there.
