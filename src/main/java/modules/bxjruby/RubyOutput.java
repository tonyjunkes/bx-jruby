package modules.bxjruby;

import java.io.Writer;

/** Per-operation capture. Zero preserves the original unlimited capture behavior. */
final class RubyOutput extends Writer {
    private final StringBuilder text = new StringBuilder();
    private final int limit;
    private boolean truncated;

    /**
     * Creates an output capture with an optional character bound.
     *
     * @param limit the maximum retained characters, or zero for unlimited capture
     */
    RubyOutput(int limit) {
        this.limit = limit;
    }

    /**
     * Appends the allowed part of a character slice and records whether excess output was discarded.
     *
     * @param chars the source character buffer
     * @param offset the first character in the source slice
     * @param length the number of characters in the source slice
     */
    @Override
    public synchronized void write(char[] chars, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, chars.length);
        int count = limit == 0 ? length : Math.min(length, Math.max(0, limit - text.length()));
        text.append(chars, offset, count);
        truncated |= count < length;
    }

    /**
     * Performs no action because capture writes are immediately visible in memory.
     */
    @Override
    public void flush() {}

    /**
     * Performs no action so captures remain readable after runtime termination.
     */
    @Override
    public void close() {}

    /**
     * Returns retained output with one marker if the capture limit was exceeded.
     *
     * @return the captured text and optional truncation marker
     */
    @Override
    public synchronized String toString() {
        return text + (truncated ? "\n[JRuby output truncated]\n" : "");
    }
}
