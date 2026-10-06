package modules.bxjruby;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBignum;
import org.jruby.RubyBoolean;
import org.jruby.RubyFixnum;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyNil;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.java.proxies.JavaProxy;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.runtime.builtin.IRubyObject;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

/** Copies collection boundaries; Ruby objects never escape without ownership. */
final class RubyValues {
    static Object fromRuby(Object value, RubySession owner, boolean handles) {
        return fromRuby(value, owner, handles, new IdentityHashMap<>());
    }

    private static Object fromRuby(Object value, RubySession owner, boolean handles, IdentityHashMap<Object, Boolean> active) {
        if (value == null || value instanceof RubyNil) return null;
        if (value instanceof RubyString string) return string.asJavaString();
        if (value instanceof RubySymbol symbol) return symbol.asJavaString();
        if (value instanceof RubyBoolean || value instanceof RubyFixnum || value instanceof RubyBignum || value instanceof RubyFloat)
            return ((IRubyObject) value).toJava(Object.class);
        if (value instanceof org.jruby.ext.bigdecimal.RubyBigDecimal decimal) {
            var context = decimal.getRuntime().getCurrentContext();
            // Java BigDecimal has no NaN/infinity; JRuby's direct conversion returns zero.
            return decimal.finite_p(context).isTrue()
                ? decimal.toJava(java.math.BigDecimal.class) : decimal.to_f(context).toJava(Double.class);
        }
        if (value instanceof JavaProxy proxy) return proxy.getObject();
        if (value instanceof RubyArray<?> array) {
            enter(value, active);
            try {
                var result = new Array();
                for (IRubyObject entry : array.toJavaArray(array.getRuntime().getCurrentContext()))
                    result.add(fromRuby(entry, owner, handles, active));
                return result;
            } finally { active.remove(value); }
        }
        if (value instanceof RubyHash hash) {
            enter(value, active);
            try {
                IStruct result = new Struct(IStruct.TYPES.LINKED_CASE_SENSITIVE);
                for (Object object : hash.directEntrySet()) {
                    var entry = (Map.Entry<?, ?>) object;
                    Object key = entry.getKey();
                    if (!(key instanceof RubyString) && !(key instanceof RubySymbol))
                        throw new BoxRuntimeException("Ruby hash keys must be strings or symbols for BoxLang conversion");
                    String name = key instanceof RubyString string ? string.asJavaString() : ((RubySymbol) key).asJavaString();
                    if (result.containsKey(name)) throw new BoxRuntimeException("Ruby hash key conversion collision: " + name);
                    result.put(name, fromRuby(entry.getValue(), owner, handles, active));
                }
                return result;
            } finally { active.remove(value); }
        }
        if (value instanceof IRubyObject ruby) {
            if (!handles) throw new BoxRuntimeException("Ruby object results require jrubySession(); one-shot runtimes are closed after evaluation");
            return owner.handle(ruby);
        }
        return value;
    }

    static IRubyObject toRuby(Object value, RubySession owner, Ruby runtime) {
        return toRuby(value, owner, runtime, new IdentityHashMap<>());
    }

    private static IRubyObject toRuby(Object value, RubySession owner, Ruby runtime, IdentityHashMap<Object, Boolean> active) {
        if (value instanceof RubyObject handle) return owner.unwrap(handle);
        // A reload creates a new module classloader, so old handles fail instanceof.
        if (value != null && value.getClass().getName().equals(RubyObject.class.getName()))
            throw new BoxRuntimeException("Ruby object belongs to another or closed module activation");
        if (value instanceof Map<?, ?> map) {
            enter(value, active);
            try {
                var result = org.jruby.api.Create.newHash(runtime.getCurrentContext());
                var keys = new HashSet<String>();
                for (var entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (!(key instanceof CharSequence) && !(key instanceof ortus.boxlang.runtime.scopes.Key))
                        throw new BoxRuntimeException("BoxLang hash keys must be strings");
                    String name = RubyOptions.name(key);
                    if (!keys.add(name)) throw new BoxRuntimeException("BoxLang hash key conversion collision: " + name);
                    result.op_aset(runtime.getCurrentContext(), runtime.newString(name), toRuby(entry.getValue(), owner, runtime, active));
                }
                return result;
            } finally { active.remove(value); }
        }
        if (value instanceof Iterable<?> entries) {
            enter(value, active);
            try {
                var result = new ArrayList<IRubyObject>();
                for (Object entry : entries) result.add(toRuby(entry, owner, runtime, active));
                return RubyArray.newArray(runtime, result);
            } finally { active.remove(value); }
        }
        if (value instanceof Object[] entries) {
            enter(value, active);
            try {
                var result = new ArrayList<IRubyObject>();
                for (Object entry : entries) result.add(toRuby(entry, owner, runtime, active));
                return RubyArray.newArray(runtime, result);
            } finally { active.remove(value); }
        }
        if (value instanceof IRubyObject) throw new BoxRuntimeException("Pass Ruby objects through session-owned handles");
        if (value instanceof java.math.BigDecimal decimal) {
            runtime.getTopSelf().callMethod(runtime.getCurrentContext(), "require", runtime.newString("bigdecimal"));
            return new org.jruby.ext.bigdecimal.RubyBigDecimal(runtime, decimal);
        }
        return JavaEmbedUtils.javaToRuby(runtime, value);
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, true) != null) throw new BoxRuntimeException("Cyclic collections cannot be converted between Ruby and BoxLang");
    }
}
