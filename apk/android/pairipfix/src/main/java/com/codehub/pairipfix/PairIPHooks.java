package com.codehub.pairipfix;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Utilidades de hook sobre las clases de PairIP. */
final class PairIPHooks {

    private PairIPHooks() {
    }

    /**
     * Neutraliza TODAS las sobrecargas de un método: el cuerpo original no se
     * ejecuta y se devuelve un valor "de éxito" acorde al tipo de retorno.
     *
     * Sirve para cortar lo que PairIP hace al detectar que la app no vino de
     * Google Play: diálogos de error, paywalls, cierres de la app y rechazos
     * de la verificación de firma/licencia.
     *
     * @return true si se hookeó al menos un método
     */
    static boolean neutralize(ClassLoader loader, String className, String methodName) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, loader);
        if (clazz == null) return false;

        boolean hooked = false;
        // Se recorre la jerarquía: algunos métodos heredan de una clase base.
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                hooked |= !XposedHelpers.hookAllMethods(current, methodName, new Success()).isEmpty();
            } catch (Throwable error) {
                XposedBridge.log("[PairIPFix] hook fallo " + className + "#" + methodName + ": " + error);
            }
        }
        return hooked;
    }

    /** Escribe un campo estático (p.ej. licenseCheckState = FULL_CHECK_OK). */
    static boolean setStaticField(ClassLoader loader, String className, String fieldName, Object value) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, loader);
        if (clazz == null) return false;
        try {
            XposedHelpers.setStaticObjectField(clazz, fieldName, value);
            return true;
        } catch (Throwable error) {
            XposedBridge.log("[PairIPFix] no se pudo escribir " + className + "." + fieldName + ": " + error);
            return false;
        }
    }

    /**
     * Hook "antes": al fijar el resultado, Xposed no llama al método original.
     * El valor depende del tipo de retorno para no romper la firma del método.
     */
    private static final class Success extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            // param.method es un Member: solo los Method tienen tipo de retorno.
            Class<?> type = void.class;
            if (param.method instanceof Method) type = ((Method) param.method).getReturnType();
            if (type == void.class || type == Void.TYPE) {
                param.setResult(null);
            } else if (type == boolean.class || type == Boolean.class) {
                param.setResult(Boolean.TRUE);
            } else if (type == int.class || type == Integer.class) {
                param.setResult(Integer.valueOf(0));
            } else if (type == long.class || type == Long.class) {
                param.setResult(Long.valueOf(0L));
            } else if (type == short.class || type == Short.class) {
                param.setResult(Short.valueOf((short) 0));
            } else if (type == byte.class || type == Byte.class) {
                param.setResult(Byte.valueOf((byte) 0));
            } else if (type == float.class || type == Float.class) {
                param.setResult(Float.valueOf(0f));
            } else if (type == double.class || type == Double.class) {
                param.setResult(Double.valueOf(0d));
            } else if (type == char.class || type == Character.class) {
                param.setResult(Character.valueOf('\0'));
            } else {
                param.setResult(null);
            }
        }
    }
}
