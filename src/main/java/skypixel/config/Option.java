package skypixel.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks the top-of-class defaults loaded from config.yml before a check starts. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Option {
    String value();
    double min() default -Double.MAX_VALUE;
    double max() default Double.MAX_VALUE;
    int minSize() default 0;
}
