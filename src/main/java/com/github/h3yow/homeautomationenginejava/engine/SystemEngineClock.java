package com.github.h3yow.homeautomationenginejava.engine;

import org.springframework.stereotype.Component;

@Component
public class SystemEngineClock implements EngineClock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
