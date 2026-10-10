package modules.bxjruby;

import java.util.ArrayList;
import java.util.List;
import org.jruby.exceptions.RaiseException;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** A Ruby failure with its captured output and original Ruby exception. */
public final class RubyExecutionException extends BoxRuntimeException {
    /** Standard output captured through completion of runtime cleanup. */
    private String stdout;
    /** Standard error captured through completion of runtime cleanup. */
    private String stderr;
    /** Source label or Ruby file path associated with the failed operation. */
    private final String source;
    /** Immutable original Ruby backtrace, empty if the cause has no Ruby trace. */
    private final List<String> rubyBacktrace;

    /**
     * Captures the failure, source and output and extracts the first Ruby backtrace in the cause chain.
     *
     * @param source the failed operation label or resolved file path
     * @param cause the original failure preserved for diagnostics
     * @param stdout the captured standard output
     * @param stderr the captured standard error
     */
    RubyExecutionException(String source, Throwable cause, String stdout, String stderr) {
        super("JRuby execution failed in " + source + ": " + cause.getMessage(), cause);
        this.source = source;
        this.stdout = stdout;
        this.stderr = stderr;
        var trace = new ArrayList<String>();
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof RaiseException raised) {
                var backtrace = raised.getException().getBacktrace();
                if (backtrace instanceof org.jruby.RubyArray<?> lines) {
                    for (Object line : lines) trace.add(line.toString());
                }
                break;
            }
        }
        this.rubyBacktrace = List.copyOf(trace);
    }

    /**
     * Returns captured standard output, including final exit-handler output when supplied.
     *
     * @return the stdout text
     */
    public String getStdout() {
        return stdout;
    }

    /**
     * Returns captured standard error, including final exit-handler output when supplied.
     *
     * @return the stderr text
     */
    public String getStderr() {
        return stderr;
    }

    /**
     * Returns the source label identifying the failed operation.
     *
     * @return the source label or resolved Ruby file path
     */
    public String getSource() {
        return source;
    }

    /**
     * Returns the original Ruby backtrace extracted from the cause chain.
     *
     * @return an immutable list, empty if no Ruby trace exists
     */
    public List<String> getRubyBacktrace() {
        return rubyBacktrace;
    }

    /**
     * Replaces operation captures with final output observed after cleanup.
     *
     * @param stdout the captured standard output
     * @param stderr the captured standard error
     */
    void captureFinalOutput(String stdout, String stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }
}
