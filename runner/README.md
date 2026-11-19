# DevBuddy runner — `scan` (Builds 2B-0a, 2B-0b-1)

The runner runs on the user's machine and reads their repository locally; the DevBuddy server
never sees code. This first component scans a repository and writes `facts.json`, a
deterministic description of which stacks it contains and of VERIFIED config facts read from
Maven and Spring Boot files (2B-0b-1). It does **not** contact the server,
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
7. **Extract config facts** for every backend `java-maven` stack (see "Config facts" below).

## What is never read

- **Secret files** (`.env`, `.env.*`): never opened.
- **Contents:** only marker files (`package.json`, `pom.xml`, `requirements.txt`, …) and the content allowlist in `RuleTable.CONTENT_FILES` are read, at most 2 MB each, without following links, all through `MarkerReader`. Larger files get `sha256: null` and a `FILE_TOO_LARGE` warning. The allowlist is `application.{yml,yaml,properties}` and `application-*.{yml,yaml,properties}` directly in a `src/main/resources/` directory, plus `Dockerfile` and `.java-version` anywhere.
- **Secret values:** a config value under a secret-looking key (`password|passwd|secret|token|api[-_]?key|private[-_]?key|credential`, case-insensitive) is dropped inside the reader. It is never stored, hashed, logged or put in an exception; only the key name, path and line are reported (`LITERAL_SECRET`).
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

## Config facts (2B-0b-1)

Plain Java, no Spring, and nothing is executed: no `mvn`, no `java`, no network. XML is parsed by `SafeXml` (SAX with line numbers; DOCTYPE rejected, no external entities or DTDs, no XInclude; the same settings as the backend `MavenManifestParser`). YAML uses the SnakeYAML node API (`compose`, never object construction) with tight `LoaderOptions` (aliases ≤ 10, depth ≤ 20, code points ≤ file size, no recursive or duplicate keys) and at most 10 000 entries. Every parser failure becomes a warning with fixed text; parser messages, which can quote file content, are never kept.

**Maven** (every module of the stack that does not have `pom` packaging):
- **Parent chain:** `relativePath` (default `../pom.xml`; a directory means `dir/pom.xml`; `<relativePath/>` means remote) is followed only among scanned files and only when `groupId:artifactId` matches, at most 10 hops. Leaving the repo stops with `PARENT_OUTSIDE_REPO`; an explicit path to nothing gives `PARENT_NOT_FOUND`.
- **`java.version`**, highest first: `maven-compiler-plugin` `<configuration>` `release`/`source`/`target` (module or local parent `build/plugins`, never `pluginManagement`) > `maven.compiler.release` > `maven.compiler.source`/`target` > `java.version`. Properties merge module > local parents > the Boot parent layer. A release beats any source/target. Losers are listed in `overridden`. `${x}` resolves only through properties that were read. `1.8` becomes `8`. Source and target that disagree give CONFLICT. A remote non-Boot parent gives UNRESOLVED unless the plugin config or a local `maven.compiler.release` decides.
- **Boot parent layer** (`BootDefaults`, verified against local starter-parent POMs): 3.0 sets `java.version` 17 with `source`/`target = ${java.version}`; 3.2–3.5, 4.0 and 4.1 set `java.version` 17 with `release = ${java.version}`. A value that comes only from this layer is DEFAULTED. Boot 2.x, 3.1 and unknown versions give UNRESOLVED.
- `spring.boot.version` (starter parent and/or an imported `spring-boot-dependencies` BOM; if they disagree: UNRESOLVED, both cited), `dependency` (`g:a[:version]`, test scope excluded), `service` (POSTGRESQL, MYSQL, MARIADB, MONGODB, REDIS, KAFKA, RABBITMQ), `app.module` (own `spring-boot-maven-plugin`: `boot-plugin-declared`; a `jar` child inheriting it from a local parent's `build/plugins`: `boot-plugin-inherited`) and `entry.mainClass` (only from the plugin's `<mainClass>`).

**Spring config** (app modules only; profile `default` or the file/document profile):
- `.properties` > `.yml` > `.yaml`, and a later document wins; losers are listed in `overridden`. Facts: `server.port`, `server.servlet.context-path`, `management.endpoints.web.base-path`, `management.server.port`, `management.server.base-path`.
- `${NAME}` / `${NAME:default}` with `NAME` matching `[A-Z][A-Z0-9_]*` gives an `env.NAME` fact (`required` when any use has no default; `default` withheld for secret-looking keys or names). `${PORT:8080}` gives value `8080`, DEFAULTED, `envVar: PORT`. A dotted or lowercase name is a property reference, so the fact is UNRESOLVED.
- `spring.datasource.url` is rebuilt from `jdbc:<kind>://host[:port]/db` or `jdbc:h2:mem:name` (user info and parameters dropped). Anything else is UNRESOLVED and never echoed. A literal password in user info or parameters raises `LITERAL_SECRET`. `spring.datasource.kind` comes from the scheme.
- With `spring-boot-starter-actuator`: `health.path` = base-path (default `/actuator`) + `/health`, and `health.effectivePath` = context-path + base-path + `/health`, or `management.server.base-path` + base-path + `/health` when `management.server.port` is set. Every line used is cited, along with the dependency line.

**Runtime JDK** (app modules): the final stage `FROM eclipse-temurin|openjdk|amazoncorretto:NN` of the nearest `Dockerfile`, and the nearest `.java-version` (module dir, stack root, repo root). A runtime older than the compile level gives CONFLICT ("runtime older than compile level"). An equal or newer runtime is cited as `jdk-runtime-compatible`. A runtime never fills an UNRESOLVED java.version; it is only cited (`jdk-runtime-unverified`).

## facts.json (schemaVersion 2)

The file is deterministic: fixed key order, 2-space indent, LF line endings, UTF-8, no timestamps, no absolute paths. The same tree gives byte-identical output.

```json
{
  "schemaVersion": 2,
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
  "facts": [
    {
      "name": "java.version", "value": "21 | null",
      "status": "KNOWN | DEFAULTED | UNRESOLVED | CONFLICT", "label": "VERIFIED",
      "module": "api", "profile": "null | default | prod",
      "evidence": [ { "rule": "maven-property", "path": "api/pom.xml", "line": 11, "commitSha": "hex | null", "sha256": "hex" } ],
      "overridden": [ { "value": "17", "evidence": [] } ],
      "candidates": [],
      "reason": "null | text"
    },
    { "name": "env.DB_PASSWORD", "required": true, "default": null, "evidence": [] }
  ],
  "warnings": [
    { "code": "SECRET_FILE_PRESENT", "path": ".env", "message": "..." },
    { "code": "LITERAL_SECRET", "key": "admin.password", "path": "api/src/main/resources/application.yml", "line": 11, "message": "..." }
  ]
}
```

- **Ordering:**
  - Stack `id` is `kind@path`, where `.` is the repository root.
  - Stacks are sorted root first, then by path.
  - `matched` includes the marker files of all members and is sorted by path.
  - Warnings are sorted by code, then path, then line, then key.
  - Facts are sorted by module, name, profile, value; `env.*` facts (which have no module) come first.
  - Evidence is sorted by path, line, rule. `overridden` is in precedence order. `overridden` and `candidates` are always arrays.
- **`sha256`** is computed over the working-tree bytes exactly as read from disk. Line-ending
  settings such as `core.autocrlf` change those bytes, so the same commit can give different
  evidence hashes on different machines. Compare hashes only between scans made on the same
  checkout. The runner's own fixtures are marked `-text` in `.gitattributes`, so they keep the
  same bytes on every OS.
- **Facts:** `reason` is required unless the status is KNOWN. `candidates` is non-empty only for CONFLICT, and then `value` is null. `envVar` appears only when set. In warnings, `key` and `line` appear only when set.

**Warning codes:**
- git: `GIT_UNAVAILABLE`, `NOT_A_GIT_REPO`, `UNCOMMITTED_CHANGES`
- paths: `PATH_OUTSIDE_ROOT`, `SYMLINK_SKIPPED`, `SUBMODULE_SKIPPED`, `SPECIAL_FILE_SKIPPED`, `PATH_UNREADABLE`
- limits: `DEPTH_LIMIT_HIT`, `FILE_LIMIT_HIT`, `FILE_TOO_LARGE`
- detection and secrets: `MARKER_UNPARSEABLE`, `DOCKERFILE_ONLY`, `SECRET_FILE_PRESENT`
- config facts: `PARENT_OUTSIDE_REPO`, `PARENT_NOT_FOUND`, `CONFIG_UNPARSEABLE`, `LITERAL_SECRET`

## Tests

`runner/mvnw test`. Fixtures live in `src/test/resources/fixtures/`. Names that the repository's `.gitignore` hides are stored as `__node_modules` and `__target` and renamed when a fixture is loaded. No `.env` is committed: tests write one containing a random canary and check that it never appears in output. Likewise `quickcart-config` commits only the placeholder `__CANARY__`, which the tests replace with a random value before scanning. The real-symlink tests are skipped (and reported as skipped) on Windows machines that can't create symlinks. Junction tests cover the same code paths there.
