package net.aeten.core.service;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 *
 * @author Thomas Pérennou
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Configured {
	boolean value() default true;
}
