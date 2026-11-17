package com.proofix.backend.runnerkey;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.exception.ResourceNotFoundException;

/**
 * Runner keys: create (plaintext returned once), list and revoke, all scoped
 * to the owner; and {@link #authenticate}, which the runner API calls on
 * every request (no cache, so a revoked or expired key fails immediately).
 * Never logs a key or its hash.
 */
@Service
public class RunnerKeyService {

    static final int MAX_NAME_LENGTH = 60;
    static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);
    private static final String NOT_FOUND = "Runner key not found";

    private final RunnerKeyRepository repository;
    private final RunnerKeyProperties properties;
    private final Clock clock;

    public RunnerKeyService(RunnerKeyRepository repository, RunnerKeyProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a key under the owner's row lock, so parallel requests cannot
     * exceed the cap on active keys.
     */
    @Transactional
    public CreatedRunnerKey create(UUID owner, String rawName) {
        String name = validName(rawName);
        if (repository.lockOwner(owner).isEmpty()) {
            throw new AuthenticationCredentialsNotFoundException("User authentication is required");
        }
        OffsetDateTime now = now();
        int cap = properties.maxActivePerUser();
        if (repository.countActive(owner, now) >= cap) {
            throw new ConflictException("You already have " + cap
                    + " active runner keys (the limit). Revoke one first.");
        }
        String plaintext = RunnerKeyCodec.generate();
        RunnerKey key = repository.save(
                new RunnerKey(owner, name, RunnerKeyCodec.hash(plaintext), now));
        return new CreatedRunnerKey(key, plaintext);
    }

    @Transactional(readOnly = true)
    public List<RunnerKey> list(UUID owner) {
        return repository.findByOwnerUserIdOrderByCreatedAtDesc(owner);
    }

    /** Idempotent: revoking a revoked key keeps the first revocation time. */
    @Transactional
    public RunnerKey revoke(UUID owner, UUID keyId) {
        RunnerKey key = repository.findByIdAndOwnerForUpdate(keyId, owner)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        key.revoke(now());
        return repository.save(key);
    }

    /**
     * Looks the key up by its hash on every call. Empty for anything that is
     * not an active key (malformed, unknown, revoked, expired). On success,
     * last_used_at is moved at most once a minute.
     */
    @Transactional
    public Optional<RunnerKeyIdentity> authenticate(String plaintext) {
        if (!RunnerKeyCodec.looksLikeKey(plaintext)) {
            return Optional.empty();
        }
        Optional<RunnerKey> found = repository.findByKeyHash(RunnerKeyCodec.hash(plaintext));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RunnerKey key = found.get();
        OffsetDateTime now = now();
        if (!key.isActive(now)) {
            return Optional.empty();
        }
        repository.touchLastUsed(key.getId(), now, now.minus(LAST_USED_RESOLUTION));
        return Optional.of(new RunnerKeyIdentity(key.getId(), key.getOwnerUserId()));
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new BadRequestException("name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("name must not contain control characters");
        }
        return name;
    }
}
