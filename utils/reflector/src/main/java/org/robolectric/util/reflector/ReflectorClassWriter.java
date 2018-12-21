package org.robolectric.util.reflector;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.V1_5;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

@SuppressWarnings("NewApi")
class ReflectorClassWriter extends ClassWriter {

  private static final Type OBJECT_TYPE = Type.getType(Object.class);
  private static final Type CLASS_TYPE = Type.getType(Class.class);
  private static final Type FIELD_TYPE = Type.getType(Field.class);
  private static final Type METHOD_TYPE = Type.getType(Method.class);
  private static final org.objectweb.asm.commons.Method CLASS$GET_DECLARED_FIELD =
      findMethod(Class.class, "getDeclaredField", new Class<?>[] {String.class});
  private static final org.objectweb.asm.commons.Method CLASS$GET_DECLARED_METHOD =
      findMethod(Class.class, "getDeclaredMethod", new Class<?>[] {String.class, Class[].class});
  private static final org.objectweb.asm.commons.Method ACCESSIBLE_OBJECT$SET_ACCESSIBLE =
      findMethod(AccessibleObject.class, "setAccessible", new Class<?>[] {boolean.class});
  private static final org.objectweb.asm.commons.Method FIELD$GET =
      findMethod(Field.class, "get", new Class<?>[] {Object.class});
  private static final org.objectweb.asm.commons.Method FIELD$SET =
      findMethod(Field.class, "set", new Class<?>[] {Object.class, Object.class});
  private static final org.objectweb.asm.commons.Method METHOD$INVOKE =
      findMethod(Method.class, "invoke", new Class<?>[] {Object.class, Object[].class});
  private static final org.objectweb.asm.commons.Method OBJECT_INIT =
      new org.objectweb.asm.commons.Method("<init>", Type.VOID_TYPE, new Type[0]);

  private static final String TARGET_FIELD = "__target__";

  private static org.objectweb.asm.commons.Method findMethod(
      Class<?> clazz, String methodName, Class<?>[] paramTypes) {
    try {
      return asmMethod(clazz.getMethod(methodName, paramTypes));
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private final Class<?> iClass;
  private final Type iType;
  private final Type reflectorType;
  private final Type targetType;

  private int nextMethodNumber = 0;
  private final Set<String> fieldRefs = new HashSet<>();

  ReflectorClassWriter(Class<?> iClass, Class<?> targetClass, String reflectorName) {
    super(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    this.iClass = iClass;
    iType = Type.getType(iClass);
    reflectorType = asType(reflectorName);
    targetType = Type.getType(targetClass);
  }

  void write() {
    int accessModifiers =
        iClass.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
    visit(
        V1_5,
        accessModifiers | ACC_SUPER | ACC_FINAL,
        reflectorType.getInternalName(),
        null,
        OBJECT_TYPE.getInternalName(),
        new String[] {iType.getInternalName()});

    writeTargetField();

    writeConstructor();

    for (Method method : iClass.getMethods()) {
      if (method.isDefault()) continue;

      Accessor accessor = method.getAnnotation(Accessor.class);
      if (accessor != null) {
        new AccessorMethodWriter(method, accessor).write();
      } else {
        new ReflectorMethodWriter(method).write();
      }
    }

    visitEnd();
  }

  private void writeTargetField() {
    visitField(ACC_PRIVATE, TARGET_FIELD, targetType.getDescriptor(), null, null);
  }

  private void writeConstructor() {
    org.objectweb.asm.commons.Method initMethod =
        new org.objectweb.asm.commons.Method("<init>", Type.VOID_TYPE, new Type[] {targetType});

    GeneratorAdapter init = new GeneratorAdapter(ACC_PUBLIC, initMethod, null, null, this);
    init.loadThis();
    init.invokeConstructor(OBJECT_TYPE, OBJECT_INIT);

    init.loadThis();
    init.loadArg(0);
    init.putField(reflectorType, TARGET_FIELD, targetType);

    init.returnValue();
    init.endMethod();
  }

  /** Generates bytecode for a setter or getter method. */
  private class AccessorMethodWriter extends GeneratorAdapterAdapter {

    private final Method iMethod;
    private final String targetFieldName;
    private final String fieldRefName;
    private final boolean isSetter;

    private AccessorMethodWriter(Method method, Accessor accessor) {
      super(method);
      this.iMethod = method;

      targetFieldName = accessor.value();
      this.fieldRefName = "field$" + targetFieldName;

      String methodName = method.getName();
      if (methodName.startsWith("get")) {
        if (method.getReturnType().equals(void.class)) {
          throw new IllegalArgumentException(method + " should have a non-void return type");
        }
        if (method.getParameterCount() != 0) {
          throw new IllegalArgumentException(method + " should take no parameters");
        }
        isSetter = false;
      } else if (methodName.startsWith("set")) {
        if (!method.getReturnType().equals(void.class)) {
          throw new IllegalArgumentException(method + " should have a void return type");
        }
        if (method.getParameterCount() != 1) {
          throw new IllegalArgumentException(method + " should take a single parameter");
        }
        isSetter = true;
      } else {
        throw new IllegalArgumentException(
            methodName + " doesn't appear to be a setter or a getter");
      }
    }

    void write() {
      // write our field to hold target field reference (but just once)...
      if (fieldRefs.add(targetFieldName)) {
        visitField(ACC_PRIVATE | ACC_STATIC, fieldRefName, FIELD_TYPE.getDescriptor(), null, null);
      }

      visitCode();

      if (isSetter) {
        // pseudocode:
        //   field_x.set(this, arg0);
        loadFieldRef();
        loadThis();
        getField(reflectorType, TARGET_FIELD, targetType);
        loadArg(0);
        Class<?> parameterType = iMethod.getParameterTypes()[0];
        if (parameterType.isPrimitive()) {
          box(Type.getType(parameterType));
        }
        invokeVirtual(FIELD_TYPE, FIELD$SET);
        returnValue();
      } else { // getter
        // pseudocode:
        //   return field_x.get(this);
        loadFieldRef();
        loadThis();
        getField(reflectorType, TARGET_FIELD, targetType);
        invokeVirtual(FIELD_TYPE, FIELD$GET);

        castForReturn(iMethod.getReturnType());
        returnValue();
      }

      endMethod();
    }

    private void loadFieldRef() {
      // pseudocode:
      //   if (field$x == null) {
      //     field$x = targetClass.getDeclaredField(name);
      //     field$x.setAccessible(true);
      //   }
      // -> field reference on stack
      getStatic(reflectorType, fieldRefName, FIELD_TYPE);
      dup();
      Label haveMethodRef = newLabel();
      ifNonNull(haveMethodRef);
      pop();

      // pseudocode:
      //   targetClass.getDeclaredField(name);
      push(targetType);
      push(targetFieldName);
      invokeVirtual(CLASS_TYPE, CLASS$GET_DECLARED_FIELD);

      // pseudocode:
      //   <field>.setAccessible(true);
      dup();
      push(true);
      invokeVirtual(FIELD_TYPE, ACCESSIBLE_OBJECT$SET_ACCESSIBLE);

      // pseudocode:
      //   field$x = method;
      dup();
      putStatic(reflectorType, fieldRefName, FIELD_TYPE);
      mark(haveMethodRef);
    }
  }

  private class ReflectorMethodWriter extends GeneratorAdapterAdapter {

    private final Method iMethod;
    private final String methodRefName;
    private final Type[] targetParamTypes;

    private ReflectorMethodWriter(Method method) {
      super(method);
      this.iMethod = method;
      int myMethodNumber = nextMethodNumber++;
      this.methodRefName = "method" + myMethodNumber;
      this.targetParamTypes = resolveParamTypes(iMethod);
    }

    void write() {
      // write field to hold method reference...
      visitField(ACC_PRIVATE | ACC_STATIC, methodRefName, METHOD_TYPE.getDescriptor(), null, null);

      visitCode();

      // pseudocode:
      //   return methodN.invoke(this, *args);
      loadOriginalMethodRef();
      loadThis();
      getField(reflectorType, TARGET_FIELD, targetType);
      loadArgArray();
      invokeVirtual(METHOD_TYPE, METHOD$INVOKE);

      castForReturn(iMethod.getReturnType());
      returnValue();

      endMethod();
    }

    private void loadOriginalMethodRef() {
      // pseudocode:
      //   if (methodN == null) {
      //     methodN = targetClass.getDeclaredMethod(name, paramTypes);
      //     methodN.setAccessible(true);
      //   }
      // -> method reference on stack
      getStatic(reflectorType, methodRefName, METHOD_TYPE);
      dup();
      Label haveMethodRef = newLabel();
      ifNonNull(haveMethodRef);
      pop();

      // pseudocode:
      //   targetClass.getDeclaredMethod(name, paramTypes);
      push(targetType);
      push(iMethod.getName());
      Type[] paramTypes = targetParamTypes;
      push(paramTypes.length);
      newArray(CLASS_TYPE);
      for (int i = 0; i < paramTypes.length; i++) {
        dup();
        push(i);
        push(paramTypes[i]);
        arrayStore(CLASS_TYPE);
      }
      invokeVirtual(CLASS_TYPE, CLASS$GET_DECLARED_METHOD);

      // pseudocode:
      //   <method>.setAccessible(true);
      dup();
      push(true);
      invokeVirtual(METHOD_TYPE, ACCESSIBLE_OBJECT$SET_ACCESSIBLE);

      // pseudocode:
      //   methodN = method;
      dup();
      putStatic(reflectorType, methodRefName, METHOD_TYPE);
      mark(haveMethodRef);
    }

    private Type[] resolveParamTypes(Method iMethod) {
      Class<?>[] iParamTypes = iMethod.getParameterTypes();
      Annotation[][] paramAnnotations = iMethod.getParameterAnnotations();

      Type[] targetParamTypes = new Type[iParamTypes.length];
      for (int i = 0; i < iParamTypes.length; i++) {
        Class<?> paramType = findWithType(paramAnnotations[i]);
        if (paramType == null) {
          paramType = iParamTypes[i];
        }
        targetParamTypes[i] = Type.getType(paramType);
      }
      return targetParamTypes;
    }

    private Class<?> findWithType(Annotation[] paramAnnotation) {
      for (Annotation annotation : paramAnnotation) {
        if (annotation instanceof WithType) {
          String withTypeName = ((WithType) annotation).value();
          try {
            return Class.forName(withTypeName, true, iClass.getClassLoader());
          } catch (ClassNotFoundException e1) {
            // it's okay, ignore
          }
        }
      }
      return null;
    }
  }

  private static String[] getInternalNames(final Class<?>[] types) {
    if (types == null) {
      return null;
    }
    String[] names = new String[types.length];
    for (int i = 0; i < names.length; ++i) {
      names[i] = Type.getType(types[i]).getInternalName();
    }
    return names;
  }

  private Type asType(String reflectorName) {
    return Type.getType("L" + reflectorName.replace('.', '/') + ";");
  }

  private static org.objectweb.asm.commons.Method asmMethod(Method method) {
    return org.objectweb.asm.commons.Method.getMethod(method);
  }

  /** Hide ugly constructor chaining. */
  private class GeneratorAdapterAdapter extends GeneratorAdapter {
    GeneratorAdapterAdapter(Method method) {
      this(org.objectweb.asm.commons.Method.getMethod(method), method.getExceptionTypes());
    }

    private GeneratorAdapterAdapter(
        org.objectweb.asm.commons.Method asmMethod, Class<?>[] exceptionTypes) {
      this(
          asmMethod,
          ReflectorClassWriter.this.visitMethod(
              Opcodes.ACC_PUBLIC,
              asmMethod.getName(),
              asmMethod.getDescriptor(),
              null,
              ReflectorClassWriter.getInternalNames(exceptionTypes)));
    }

    private GeneratorAdapterAdapter(
        org.objectweb.asm.commons.Method asmMethod, MethodVisitor methodVisitor) {
      super(
          Opcodes.ASM6,
          methodVisitor,
          Opcodes.ACC_PUBLIC,
          asmMethod.getName(),
          asmMethod.getDescriptor());
    }

    protected void castForReturn(Class<?> returnType) {
      if (returnType.isPrimitive()) {
        unbox(Type.getType(returnType));
      } else {
        checkCast(Type.getType(returnType));
      }
    }
  }
}