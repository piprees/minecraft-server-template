package com.customdimensions.client;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatedTest {

    /** Records which level a call landed on, without depending on an slf4j build. */
    private static final class Recorder implements InvocationHandler {

        private final List<String> levels = new ArrayList<>();
        private final List<Object[]> arguments = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            this.levels.add(method.getName());
            this.arguments.add(args);
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) {
                return true;
            }
            return returns == String.class ? "recorder" : null;
        }
    }

    private static Logger loggerFor(Recorder recorder) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class}, recorder);
    }

    @Test
    void firstGoesToInfo() {
        Recorder recorder = new Recorder();
        Repeated.log(loggerFor(recorder), true, "{} passes={}", "marker", 1);
        assertEquals(List.of("info"), recorder.levels);
    }

    @Test
    void everyRepeatGoesToDebug() {
        Recorder recorder = new Recorder();
        Logger logger = loggerFor(recorder);
        Repeated.log(logger, false, "{} passes={}", "marker", 601);
        Repeated.log(logger, false, "{} passes={}", "marker", 1201);
        assertEquals(List.of("debug", "debug"), recorder.levels);
    }

    /** The format and its arguments reach the logger unchanged at either level. */
    @Test
    void argumentsSurviveTheChoice() {
        Recorder recorder = new Recorder();
        Repeated.log(loggerFor(recorder), false, "{} dimension={}", "marker", "adventure:x");
        Object[] passed = recorder.arguments.get(0);
        assertEquals("{} dimension={}", passed[0]);
        assertTrue(passed[1] instanceof Object[]);
        assertEquals(2, ((Object[]) passed[1]).length);
        assertEquals("adventure:x", ((Object[]) passed[1])[1]);
    }
}
