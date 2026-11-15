package com.proofix.backend.exception;

/**
 * The repository may not be analyzed on this path (for example, a private
 * repository submitted for one-off URL analysis). Mapped to 403 Forbidden.
 */
public class RepositoryAccessDeniedException extends RuntimeException {

    public RepositoryAccessDeniedException(String message) {
        super(message);
    }
}
