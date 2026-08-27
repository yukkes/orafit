package io.github.orafit.translation;

/**
 * Checked fail-closed result used when Orafit cannot preserve Oracle semantics safely.
 *
 * <p>The stable {@linkplain #code() translation code} identifies the rejected semantic boundary;
 * the human-readable exception message may provide additional context and is not a compatibility
 * identifier.
 */
public final class TranslationException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;

    /**
     * Creates a translation failure.
     *
     * @param code stable machine-readable failure category
     * @param message human-readable reason for rejecting the translation
     */
    public TranslationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Creates a translation failure caused by a lower-level parser or conversion failure.
     *
     * @param code stable machine-readable failure category
     * @param message human-readable reason for rejecting the translation
     * @param cause underlying failure
     */
    public TranslationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Returns the machine-readable fail-closed category used by tests and JDBC error mapping.
     *
     * @return stable translation failure code
     */
    public String code() {
        return code;
    }
}
