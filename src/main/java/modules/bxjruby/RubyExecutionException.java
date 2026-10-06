package modules.bxjruby;

import java.util.ArrayList;
import java.util.List;
import org.jruby.exceptions.RaiseException;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** A Ruby failure with its captured output and original Ruby exception. */
public final class RubyExecutionException extends BoxRuntimeException {
    private String stdout;
    private String stderr;
    private final String source;
    private final List<String> rubyBacktrace;

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

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public String getSource() {
        return source;
    }

    public List<String> getRubyBacktrace() {
        return rubyBacktrace;
    }

    void captureFinalOutput(String stdout, String stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }
}
