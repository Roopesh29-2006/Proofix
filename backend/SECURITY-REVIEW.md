# DevBuddy Maven Manifest Parser Security Review

## Assessment

**NOT APPROVED - FIXES REQUIRED**

The XML parser configuration is strongly hardened, but three medium-severity
correctness and evidence issues should be resolved before integration with the
snapshot intelligence pipeline.

## Findings

| # | Severity | File | Lines | Finding | Confidence |
|---|---|---|---:|---|---:|
| 1 | MEDIUM | `src/main/java/com/proofix/backend/intelligence/parser/MavenManifestParser.java` | 397-407 | A value containing one valid `${...}` token and another malformed token can be classified as `PROPERTY_REFERENCE` instead of `UNRESOLVED`. | 9/10 |
| 2 | MEDIUM | `src/main/java/com/proofix/backend/intelligence/parser/MavenManifestParser.java` | 130-142 | Root and child elements are matched by local name only, so an attacker-controlled XML namespace can be accepted as a Maven POM. | 9/10 |
| 3 | MEDIUM | `src/main/java/com/proofix/backend/intelligence/parser/MavenManifestParser.java` | 192-202 | Dependency findings preserve only the repository path; snapshot and content identity fields are always null. | 9/10 |

## XML security results

The parser was verified to:

- Enable namespace-aware parsing.
- Reject DOCTYPE declarations.
- Disable external general entities.
- Disable external parameter entities.
- Disable external DTD loading.
- Enable secure processing.
- Set external DTD access to an empty value.
- Set external schema access to an empty value.
- Disable XInclude.
- Disable entity expansion.
- Install an entity resolver that returns an empty input source.
- Return a structured security status if required parser configuration fails.

No XML-security bypass was identified during the review.

## Required corrections

1. Validate the Maven namespace at the root and consistently handle child
   namespaces.
2. Validate every `${...}` expression before returning
   `PROPERTY_REFERENCE`; classify malformed expressions as `UNRESOLVED`.
3. **Deferred architectural improvement:** attach immutable snapshot provenance
   through the future snapshot intelligence orchestration/evidence-enrichment
   boundary, where snapshot ID, snapshot file ID, GitHub SHA and content hash
   are available. `MavenManifestParser` remains standalone.

## Test coverage

The existing 12 focused tests cover:

- Root and nested POM path support.
- Case and separator normalization.
- Unsupported XML filenames.
- Java properties and compiler plugin values.
- Spring Boot parent and plugin detection.
- Explicit, property-reference and missing dependency versions.
- Dependency scope, optional, classifier and type.
- Multiple dependencies.
- Dependency-management exclusion.
- Empty, null and malformed content.
- Standard Maven namespace handling.
- DOCTYPE, XXE, external DTD and XInclude cases.

Additional direct tests are recommended for:

- Null repository paths.
- Unexpected root and child namespaces.
- Conflicting Java version declarations.
- Missing dependency coordinates.
- Mixed valid and malformed property placeholders.
- Immutable multi-module evidence provenance.

## Integration decision

The parser should not proceed to a parser registry until namespace validation
and complete placeholder classification are resolved. Evidence provenance is
deferred to the future snapshot intelligence orchestration/evidence-enrichment
boundary. The parser remains independent of Spring services, GitHub clients,
database access, Maven execution and LLM frameworks.

## Verification

The security review itself did not rerun tests. Previously reported results
were 12 focused tests and 90 full-suite tests.

## Commit status

`SECURITY-REVIEW.md` was created as requested. No commit was possible because
`C:\Users\Welcome\OneDrive\Desktop\Proofix\backend` is not inside a Git
repository.

## DevBuddy Gradle Manifest Parser Security and Correctness Audit

### Assessment

**APPROVED WITH NON-BLOCKING IMPROVEMENTS**

No exploitable security vulnerability was identified. One medium-severity
correctness issue must be fixed before relying on the parser for accurate
multi-block Gradle analysis.

### Finding

| # | Severity | File | Lines | Finding | Confidence |
|---|---|---|---:|---|---:|
| 1 | MEDIUM | `src/main/java/com/proofix/backend/intelligence/parser/GradleManifestParser.java` | 87-115 | The active dependency-block state is never cleared after the first `dependencies {}` block. A later top-level block containing a supported configuration name can therefore be falsely reported as a dependency. | 10/10 |

Example:

```groovy
dependencies {
    implementation 'real:library:1.0'
}

repositories {
    implementation 'fake:not-a-dependency:2.0'
}
```

### Security results

The parser was verified to:

- Perform no script execution or Groovy/Kotlin evaluation.
- Invoke no shell, process, Gradle command or external parser.
- Perform no network or arbitrary file access.
- Use no reflection or deserialization.
- Process supplied content with a linear scanner and bounded token storage.
- Avoid unrestricted regular expressions and recursive parsing.

No security bypass was identified.

### Test coverage summary

The focused Gradle test suite contains 11 tests covering path support, null and
empty content, Groovy and Kotlin dependency notation, all six supported
configurations, version statuses, comments, unrelated strings, unsupported
declarations, malformed declarations, evidence paths and registry selection.

Not directly covered are range versions, additional dynamic-version forms,
classifier/type notation, map-style dependencies, `add(...)` notation,
unterminated input, nested dependency blocks, and declarations in later
top-level blocks.

### Recommended correction

Track the actual opening and closing span of the active `dependencies` block,
or clear the active block state when its matching closing brace is reached.
Add a regression test proving that supported configuration names in later
top-level blocks are not extracted.

### Verification

The implementation test results were:

- `.\mvnw.cmd -Dtest=GradleManifestParserTest test`: 11 passed.
- `.\mvnw.cmd -Dtest=MavenManifestParserTest test`: 16 passed.
- `.\mvnw.cmd -Dtest=DependencyManifestParserRegistryTest test`: 9 passed.
- `.\mvnw.cmd test`: 114 passed.

The audit itself was inspection-only.
