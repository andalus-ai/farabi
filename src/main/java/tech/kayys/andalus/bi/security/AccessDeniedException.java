package tech.kayys.andalus.bi.security;

public final class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) { super(message); }
}
