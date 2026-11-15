package com.igrobar.createdynmap;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Рефлексия с кешем.
 *
 * На Youer каждый поиск метода/поля из плагина проходит через ремаппер сервера
 * (getMethods, getName и т.д. перехватываются) и стоит очень дорого. Поэтому поиск
 * делается один раз на (класс, имя, типы аргументов), а дальше используется готовый Method.
 */
final class Reflect {
    static final Object[] NO_ARGS = new Object[0];
    private static final Object MISSING = new Object();

    private static final Map<Key, Object> METHODS = new ConcurrentHashMap<>();
    private static final Map<Key, Object> STATIC_METHODS = new ConcurrentHashMap<>();
    private static final Map<Key, Object> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<>();

    private record Key(Class<?> owner, String name, List<Class<?>> args) {
    }

    private Reflect() {
    }

    static void clear() {
        METHODS.clear();
        STATIC_METHODS.clear();
        FIELDS.clear();
        CLASSES.clear();
    }

    static Class<?> type(String name) throws ClassNotFoundException {
        Class<?> cached = CLASSES.get(name);
        if (cached != null) {
            return cached;
        }
        Class<?> loaded = Class.forName(name);
        CLASSES.put(name, loaded);
        return loaded;
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) {
            return null;
        }
        Method method = method(target.getClass(), name, args);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
        }
        return method.invoke(target, args);
    }

    static Object optionalCall(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Object callStatic(Class<?> owner, String name, Object... args) throws Exception {
        Key key = new Key(owner, name, argTypes(args));
        Object cached = STATIC_METHODS.get(key);
        if (cached == null) {
            cached = MISSING;
            for (Method method : owner.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && matches(method, name, args)) {
                    cached = method;
                    break;
                }
            }
            STATIC_METHODS.put(key, cached);
        }
        if (cached == MISSING) {
            throw new NoSuchMethodException(owner.getName() + "." + name + "/" + args.length);
        }
        return ((Method) cached).invoke(null, args);
    }

    /** Сначала поле (публичное или объявленное), если его нет или оно null — метод без аргументов. */
    static Object fieldOrMethod(Object target, String fieldName, String methodName) throws Exception {
        if (target == null) {
            return null;
        }
        Field field = field(target.getClass(), fieldName);
        if (field != null) {
            Object value = field.get(target);
            if (value != null) {
                return value;
            }
        }
        Method method = method(target.getClass(), methodName, NO_ARGS);
        return method == null ? null : method.invoke(target);
    }

    static Object staticField(Class<?> owner, String name) throws Exception {
        Field field = field(owner, name);
        if (field == null) {
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
        return field.get(null);
    }

    private static Field field(Class<?> owner, String name) {
        Key key = new Key(owner, name, List.of());
        Object cached = FIELDS.get(key);
        if (cached == null) {
            cached = MISSING;
            try {
                cached = owner.getField(name);
            } catch (NoSuchFieldException | RuntimeException notPublic) {
                for (Class<?> type = owner; type != null && cached == MISSING; type = type.getSuperclass()) {
                    try {
                        Field declared = type.getDeclaredField(name);
                        declared.setAccessible(true);
                        cached = declared;
                    } catch (NoSuchFieldException | RuntimeException ignored) {
                        // нет такого поля или нет доступа — ищем дальше
                    }
                }
            }
            FIELDS.put(key, cached);
        }
        return cached == MISSING ? null : (Field) cached;
    }

    static Method method(Class<?> owner, String name, Object[] args) {
        Key key = new Key(owner, name, argTypes(args));
        Object cached = METHODS.get(key);
        if (cached == null) {
            Method found = findPublicMethod(owner, name, args);
            if (found == null) {
                found = findAnyPublicMethod(owner, name, args);
            }
            cached = found == null ? MISSING : found;
            METHODS.put(key, cached);
        }
        return cached == MISSING ? null : (Method) cached;
    }

    /** Тот же порядок поиска, что был в 1.0.0.5: интерфейсы, затем публичные методы публичных классов. */
    private static Method findPublicMethod(Class<?> owner, String name, Object[] args) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                Method method = findInInterface(iface, name, args);
                if (method != null) {
                    return method;
                }
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && matches(method, name, args)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findInInterface(Class<?> iface, String name, Object[] args) {
        for (Method method : iface.getMethods()) {
            if (matches(method, name, args)) {
                return method;
            }
        }
        for (Class<?> parent : iface.getInterfaces()) {
            Method method = findInInterface(parent, name, args);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static Method findAnyPublicMethod(Class<?> owner, String name, Object[] args) {
        for (Method method : owner.getMethods()) {
            if (!matches(method, name, args)) {
                continue;
            }
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {
                // если не дали доступ — invoke сам бросит исключение, как и раньше
            }
            return method;
        }
        return null;
    }

    private static List<Class<?>> argTypes(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        return Arrays.asList(types);
    }

    private static boolean matches(Method method, String name, Object[] args) {
        if (method.getParameterCount() != args.length || !method.getName().equals(name)) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (args[i] != null && !wrap(params[i]).isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Character.TYPE) return Character.class;
        return type;
    }

    static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    static Object optionalValue(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    static String componentText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        Object text = optionalCall(value, "getString");
        if (text == null) {
            text = optionalCall(value, "getContents");
        }
        String result = text == null ? value.toString() : text.toString();
        return result == null || result.isBlank() ? fallback : result;
    }

    static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    static Throwable unwrap(Throwable throwable) {
        while (throwable instanceof InvocationTargetException ite && ite.getCause() != null) {
            throwable = ite.getCause();
        }
        return throwable;
    }
}
