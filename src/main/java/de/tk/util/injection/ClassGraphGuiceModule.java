package de.tk.util.injection;

import com.google.inject.AbstractModule;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Google Guice module that scans packages using ClassGraph for {@link ImplementationClass}
 * annotations on classes or constructors, binds each implementation to its API interface,
 * and registers static {@link Inject} holder classes via {@code requestStaticInjection}.
 */
public class ClassGraphGuiceModule extends AbstractModule {

    private final ClassLoader customClassLoader;
    private final String[] scanPackages;

    public ClassGraphGuiceModule(String... scanPackages) {
        this(null, scanPackages);
    }

    public ClassGraphGuiceModule(ClassLoader customClassLoader, String... scanPackages) {
        this.customClassLoader = customClassLoader;
        this.scanPackages = scanPackages != null ? scanPackages.clone() : new String[0];
    }

    @Override
    protected void configure() {
        ClassGraph classGraph = new ClassGraph()
                .enableAllInfo()
                .ignoreFieldVisibility()
                .ignoreMethodVisibility();

        if (customClassLoader != null) {
            classGraph.overrideClassLoaders(customClassLoader);
        }
        if (scanPackages.length > 0) {
            classGraph.acceptPackages(scanPackages);
        }

        Set<String> boundImplementationNames = new LinkedHashSet<>();
        Set<String> staticHolders = new LinkedHashSet<>();

        try (ScanResult scanResult = classGraph.scan()) {
            // 1. Discover classes with @ImplementationClass on the type or on any constructor
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                if (classInfo.isInterface() || classInfo.isAbstract() || classInfo.isAnnotation()) {
                    continue;
                }
                boolean hasTypeAnnotation = classInfo.hasAnnotation(ImplementationClass.class.getName());
                boolean hasConstructorAnnotation = false;
                for (MethodInfo ctorInfo : classInfo.getConstructorInfo()) {
                    if (ctorInfo.hasAnnotation(ImplementationClass.class.getName())) {
                        hasConstructorAnnotation = true;
                        break;
                    }
                }
                if (hasTypeAnnotation || hasConstructorAnnotation) {
                    boundImplementationNames.add(classInfo.getName());
                }

                // 2. Discover static @jakarta.inject.Inject fields (e.g. ProviderHolder in API interfaces)
                for (FieldInfo fieldInfo : classInfo.getDeclaredFieldInfo()) {
                    if (fieldInfo.isStatic() && fieldInfo.hasAnnotation(Inject.class.getName())) {
                        staticHolders.add(classInfo.getName());
                    }
                }
            }
        }

        ClassLoader loader = customClassLoader != null
                ? customClassLoader
                : Thread.currentThread().getContextClassLoader();

        for (String implClassName : boundImplementationNames) {
            try {
                Class<?> implClass = Class.forName(implClassName, true, loader);
                bindImplementationClass(implClass);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to load implementation class: " + implClassName, e);
            }
        }

        for (String holderClassName : staticHolders) {
            try {
                Class<?> holderClass = Class.forName(holderClassName, true, loader);
                requestStaticInjection(holderClass);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to load static holder class: " + holderClassName, e);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void bindImplementationClass(Class<?> implClass) {
        ImplementationClass typeAnn = implClass.getAnnotation(ImplementationClass.class);
        if (typeAnn != null && typeAnn.value() != Void.class) {
            Class apiInterface = typeAnn.value();
            bind(apiInterface).to(implClass);
            return;
        }
        Class<?>[] interfaces = implClass.getInterfaces();
        if (interfaces.length == 0) {
            throw new IllegalStateException(
                    "@ImplementationClass " + implClass.getName() + " does not implement any API interface");
        }
        for (Class iface : interfaces) {
            bind(iface).to(implClass);
        }
    }
}
