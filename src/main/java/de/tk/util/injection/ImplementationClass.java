package de.tk.util.injection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a provider/service implementation class or its constructor in the implementation layer
 * so that {@link ClassGraphGuiceModule} automatically binds it to its API interface at startup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface ImplementationClass {
    Class<?> value() default Void.class;
}
