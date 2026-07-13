package nosql.common;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库存储的值对象，支持多种数据类型，并且是可序列化的。
 */
public class DbValue implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        STRING, NUMBER, LIST, SET, MAP
    }

    private final Type type;
    private final Object value;

    public DbValue(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    public Type getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public String asString() {
        if (type != Type.STRING) {
            throw new ClassCastException("Value is not a String: " + type);
        }
        return (String) value;
    }

    public Number asNumber() {
        if (type != Type.NUMBER) {
            throw new ClassCastException("Value is not a Number: " + type);
        }
        return (Number) value;
    }

    @SuppressWarnings("unchecked")
    public List<Object> asList() {
        if (type != Type.LIST) {
            throw new ClassCastException("Value is not a List: " + type);
        }
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    public Set<Object> asSet() {
        if (type != Type.SET) {
            throw new ClassCastException("Value is not a Set: " + type);
        }
        return (Set<Object>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap() {
        if (type != Type.MAP) {
            throw new ClassCastException("Value is not a Map: " + type);
        }
        return (Map<String, Object>) value;
    }

    @Override
    public String toString() {
        return "DbValue{type=" + type + ", value=" + value + "}";
    }
}
