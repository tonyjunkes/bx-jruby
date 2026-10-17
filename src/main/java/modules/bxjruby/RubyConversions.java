package modules.bxjruby;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.jruby.Ruby;
import org.jruby.RubyString;
import org.jruby.runtime.builtin.IRubyObject;
import ortus.boxlang.runtime.types.DateTime;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** Explicit conversions complement the stable automatic collection/scalar conversion contract. */
final class RubyConversions {
    /** Prevents construction of this conversion utility. */
    private RubyConversions() {}

    /**
     * Creates an explicitly typed Ruby value.
     * @param value native input or an owned handle
     * @param type the requested conversion name
     * @param owner the owning session
     * @param runtime its Ruby runtime
     * @return the Ruby value, which the caller wraps in a handle
     */
    static IRubyObject toRuby(Object value, String type, RubySession owner, Ruby runtime) {
        var context = runtime.getCurrentContext();
        return switch (normalizeType(type)) {
            case "auto" -> RubyValues.toRuby(value, owner, runtime);
            case "binary" -> {
                if (!(value instanceof byte[] bytes)) {
                    throw new BoxRuntimeException("Ruby binary conversion requires binary data");
                }
                yield RubyString.newString(runtime, bytes.clone());
            }
            case "string", "symbol" -> {
                if (!(value instanceof String text)) {
                    throw new BoxRuntimeException("Ruby string/symbol conversion requires a string");
                }
                yield type.equalsIgnoreCase("symbol") ? runtime.newSymbol(text) : runtime.newString(text);
            }
            case "date", "time" -> {
                if (value == null) {
                    throw new BoxRuntimeException("Ruby date/time conversion requires an ISO value or DateTime");
                }
                boolean date = type.equalsIgnoreCase("date");
                String text = value instanceof DateTime dt
                    ? (date ? dt.toLocalDate().toString() : dt.getWrapped().toOffsetDateTime().toString())
                    : value.toString();
                runtime.getTopSelf().callMethod(context, "require", runtime.newString(date ? "date" : "time"));
                yield runtime.getClass(date ? "Date" : "Time").callMethod(context, "iso8601", runtime.newString(text));
            }
            default -> throw new BoxRuntimeException("Unknown Ruby conversion: " + type);
        };
    }

    /**
     * Copies a typed Ruby value without silently coercing an unrelated Ruby class.
     * @param value the unwrapped owned value
     * @param type the requested conversion name
     * @param owner the owning session
     * @return a copied native value
     */
    static Object toBoxLang(IRubyObject value, String type, RubySession owner) {
        var runtime = value.getRuntime();
        var context = runtime.getCurrentContext();
        return switch (normalizeType(type)) {
            case "auto" -> RubyValues.fromRuby(value, owner, true);
            case "binary" -> {
                if (!(value instanceof RubyString string)) {
                    throw new BoxRuntimeException("Ruby binary conversion requires a Ruby String");
                }
                yield string.getBytes().clone();
            }
            case "string", "symbol" -> {
                if (!(value instanceof RubyString) && !(value instanceof org.jruby.RubySymbol)) {
                    throw new BoxRuntimeException("Ruby text conversion requires String or Symbol");
                }
                IRubyObject text = value.callMethod(context, "to_s");
                if (!text.callMethod(context, "valid_encoding?").isTrue()) {
                    throw new BoxRuntimeException("Ruby text contains an invalid byte sequence; use binary conversion");
                }
                yield text.callMethod(context, "encode", runtime.newString("UTF-8")).asJavaString();
            }
            case "date", "time" -> {
                boolean date = type.equalsIgnoreCase("date");
                runtime.getTopSelf().callMethod(context, "require", runtime.newString(date ? "date" : "time"));
                if (!runtime.getClass(date ? "Date" : "Time").isInstance(value)) {
                    throw new BoxRuntimeException("Ruby " + type + " conversion has the wrong Ruby type");
                }
                String text = date ? value.callMethod(context, "iso8601").asJavaString()
                    : value.callMethod(context, "iso8601", runtime.newFixnum(9)).asJavaString();
                yield date ? new DateTime(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC))
                    : new DateTime(OffsetDateTime.parse(text));
            }
            default -> throw new BoxRuntimeException("Unknown Ruby conversion: " + type);
        };
    }

    /**
     * Normalizes an explicit conversion selector with a useful error for missing input.
     * @param type the supplied selector
     * @return its lowercase spelling
     */
    private static String normalizeType(String type) {
        if (type == null) {
            throw new BoxRuntimeException("Ruby conversion type is required");
        }
        return type.toLowerCase(Locale.ROOT);
    }
}
