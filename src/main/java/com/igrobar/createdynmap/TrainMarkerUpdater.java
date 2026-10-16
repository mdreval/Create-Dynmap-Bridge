package com.igrobar.createdynmap;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TrainMarkerUpdater {
    private static final Set<String> warned = Collections.synchronizedSet(new HashSet());

    private TrainMarkerUpdater() {
    }

    public static void update(Object object, Object object2, Object object3, Set<String> set) {
        try {
            Object object4 = TrainMarkerUpdater.invoke(object, "railwayDataSnapshot", new Object[0]);
            if (object4 == null) {
                return;
            }
            Object object5 = TrainMarkerUpdater.field(object4, "trains");
            if (!(object5 instanceof Map)) {
                return;
            }
            Map map = (Map)object5;
            Method method = TrainMarkerUpdater.declared(object.getClass(), "dynmapWorld", String.class);
            Method method2 = TrainMarkerUpdater.declared(object.getClass(), "upsert", Object.class, Object.class, String.class, String.class, String.class, String.class, Double.TYPE, Double.TYPE, Double.TYPE);
            Method method3 = TrainMarkerUpdater.declared(object.getClass(), "dimensionId", Object.class);
            int n = 0;
            int n2 = 0;
            for (Object v : map.values()) {
                try {
                    Object object6 = TrainMarkerUpdater.fieldOrMethod(v, "id", "getId");
                    if (object6 == null) continue;
                    String string = TrainMarkerUpdater.safe(object6.toString());
                    Object object7 = TrainMarkerUpdater.fieldOrMethod(v, "name", "getName");
                    String string2 = TrainMarkerUpdater.componentText(object7, "Train " + string);
                    Object object8 = TrainMarkerUpdater.fieldOrMethod(v, "carriages", "getCarriages");
                    if (!(object8 instanceof Iterable)) continue;
                    Iterable iterable = (Iterable)object8;
                    boolean bl = false;
                    for (Object t : iterable) {
                        Object object9 = TrainMarkerUpdater.optionalInvoke(t, "getPresentDimensions", new Object[0]);
                        if (!(object9 instanceof Iterable)) continue;
                        Iterable iterable2 = (Iterable)object9;
                        for (Object t2 : iterable2) {
                            Object object10 = TrainMarkerUpdater.optionalInvoke(t, "getPositionInDimension", t2);
                            Object object11 = TrainMarkerUpdater.optionalValue(object10);
                            if (object11 == null) continue;
                            double d = TrainMarkerUpdater.coord(object11, "getX", "x");
                            double d2 = TrainMarkerUpdater.coord(object11, "getY", "y");
                            double d3 = TrainMarkerUpdater.coord(object11, "getZ", "z");
                            String string3 = String.valueOf(TrainMarkerUpdater.invoke(method3, object, t2));
                            String string4 = String.valueOf(TrainMarkerUpdater.invoke(method, object, string3));
                            String string5 = "train_" + string + "_" + TrainMarkerUpdater.safe(string4);
                            Object object12 = string2;
                            Object object13 = TrainMarkerUpdater.optionalInvoke(v, "getCurrentStation", new Object[0]);
                            Object object14 = object13 == null ? null : TrainMarkerUpdater.fieldOrMethod(object13, "name", "getName");
                            String string6 = TrainMarkerUpdater.componentText(object14, "");
                            if (!string6.isBlank()) {
                                object12 = (String)object12 + " \u2192 " + string6;
                            }
                            TrainMarkerUpdater.invoke(method2, object, object2, object3, string5, object12, "Create train", string4, d, d2, d3);
                            set.add(string5);
                            ++n2;
                            ++n;
                            bl = true;
                            break;
                        }
                        if (!bl) continue;
                    }
                }
                catch (Throwable throwable) {
                    TrainMarkerUpdater.warn(object, "train-position-" + v.getClass().getName(), "Could not resolve saved Create train position: " + TrainMarkerUpdater.root(throwable));
                }
            }
            if (n2 == 0) {
                TrainMarkerUpdater.warn(object, "no-train-positions", "Create has " + map.size() + " train(s), but no carriage returned a saved dimensional position. Train markers will be retried.");
            } else {
                warned.remove("no-train-positions");
            }
        }
        catch (Throwable throwable) {
            TrainMarkerUpdater.warn(object, "train-updater", "Train marker update failed: " + TrainMarkerUpdater.root(throwable));
        }
    }

    private static Object field(Object object, String string) throws Exception {
        if (object == null) {
            return null;
        }
        for (Class<?> clazz = object.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(string);
                field.setAccessible(true);
                return field.get(object);
            }
            catch (NoSuchFieldException noSuchFieldException) {
                continue;
            }
        }
        return null;
    }

    private static Object fieldOrMethod(Object object, String string, String string2) throws Exception {
        Object object2 = TrainMarkerUpdater.field(object, string);
        if (object2 != null) {
            return object2;
        }
        return TrainMarkerUpdater.optionalInvoke(object, string2, new Object[0]);
    }

    private static Object optionalInvoke(Object object, String string, Object ... objectArray) {
        if (object == null) {
            return null;
        }
        try {
            for (Method method : object.getClass().getMethods()) {
                if (!method.getName().equals(string) || method.getParameterCount() != objectArray.length) continue;
                boolean bl = true;
                Class<?>[] classArray = method.getParameterTypes();
                for (int i = 0; i < classArray.length; ++i) {
                    if (objectArray[i] == null || TrainMarkerUpdater.box(classArray[i]).isAssignableFrom(objectArray[i].getClass())) continue;
                    bl = false;
                    break;
                }
                if (!bl) continue;
                return method.invoke(object, objectArray);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return null;
    }

    private static Object optionalValue(Object object) {
        if (object instanceof Optional) {
            Optional optional = (Optional)object;
            return optional.orElse(null);
        }
        return object;
    }

    private static double coord(Object object, String string, String string2) throws Exception {
        Object object2 = TrainMarkerUpdater.optionalInvoke(object, string, new Object[0]);
        if (object2 == null) {
            object2 = TrainMarkerUpdater.field(object, string2);
        }
        if (object2 instanceof Number) {
            Number number = (Number)object2;
            return number.doubleValue();
        }
        throw new IllegalStateException("Missing coordinate " + string + " on " + object.getClass().getName());
    }

    private static Method declared(Class<?> clazz, String string, Class<?> ... classArray) throws Exception {
        for (Class<?> clazz2 = clazz; clazz2 != null; clazz2 = clazz2.getSuperclass()) {
            try {
                Method method = clazz2.getDeclaredMethod(string, classArray);
                method.setAccessible(true);
                return method;
            }
            catch (NoSuchMethodException noSuchMethodException) {
                continue;
            }
        }
        throw new NoSuchMethodException(string);
    }

    private static Object invoke(Object object2, String string, Object ... objectArray) throws Exception {
        Method method = TrainMarkerUpdater.declared(object2.getClass(), string, (Class[])Arrays.stream(objectArray).map(object -> object == null ? Object.class : object.getClass()).toArray(Class[]::new));
        return method.invoke(object2, objectArray);
    }

    private static Object invoke(Method method, Object object, Object ... objectArray) throws Exception {
        return method.invoke(object, objectArray);
    }

    private static Class<?> box(Class<?> clazz) {
        if (!clazz.isPrimitive()) {
            return clazz;
        }
        if (clazz == Integer.TYPE) {
            return Integer.class;
        }
        if (clazz == Long.TYPE) {
            return Long.class;
        }
        if (clazz == Double.TYPE) {
            return Double.class;
        }
        if (clazz == Boolean.TYPE) {
            return Boolean.class;
        }
        if (clazz == Float.TYPE) {
            return Float.class;
        }
        if (clazz == Short.TYPE) {
            return Short.class;
        }
        if (clazz == Byte.TYPE) {
            return Byte.class;
        }
        if (clazz == Character.TYPE) {
            return Character.class;
        }
        return clazz;
    }

    private static String componentText(Object object, String string) {
        if (object == null) {
            return string;
        }
        Object object2 = TrainMarkerUpdater.optionalInvoke(object, "getString", new Object[0]);
        if (object2 == null) {
            object2 = TrainMarkerUpdater.optionalInvoke(object, "getContents", new Object[0]);
        }
        String string2 = object2 == null ? object.toString() : object2.toString();
        return string2 == null || string2.isBlank() ? string : string2;
    }

    private static String safe(String string) {
        return string == null ? "unknown" : string.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String root(Throwable throwable) {
        InvocationTargetException invocationTargetException;
        while (throwable instanceof InvocationTargetException && (invocationTargetException = (InvocationTargetException)throwable).getCause() != null) {
            throwable = invocationTargetException.getCause();
        }
        return throwable.toString();
    }

    private static void warn(Object object, String string, String string2) {
        if (!warned.add(string)) {
            return;
        }
        try {
            Object object2 = object.getClass().getMethod("getLogger", new Class[0]).invoke(object, new Object[0]);
            object2.getClass().getMethod("warning", String.class).invoke(object2, string2);
        }
        catch (Throwable throwable) {
            System.err.println(string2);
        }
    }
}

