package tech.kayys.farabi.engine;

/** Minimal audit port. Events carry identifiers and digests, never prompts or result data. */
public final class Audit {
    private Audit() {}
    public record Event(String action, String workspaceId, String subjectId, String detail) {}
    @FunctionalInterface public interface Sink { void record(Event event); }
    public static final Sink NONE = event -> { };
}
