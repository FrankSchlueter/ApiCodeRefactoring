package de.tk.dependencyanalyse.transformation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks classes, interfaces, methods, fields, and constructors that are part of the module API
 * consumed by external modules.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.CONSTRUCTOR
})
public @interface Api {
    /**
     * If {@code true}, the annotated class (including its transitive hull) is API-conformant
     * and can be used directly as part of the module API without interface extraction.
     * If {@code false}, usages of this implementation class are replaced by an API interface.
     */
    boolean isApiKonform() default false;
}
