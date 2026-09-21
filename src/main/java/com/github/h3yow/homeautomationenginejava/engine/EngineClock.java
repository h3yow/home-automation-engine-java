package com.github.h3yow.homeautomationenginejava.engine;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 统一时钟：所有时间判定（时间窗口、超时、排序）只从这里取，
 * 测试可替换为固定时钟以保证确定性。
 */
@Component
public class EngineClock {

    private volatile Long fixedMillis;
    private volatile ZoneId zone = ZoneId.of("UTC");

    public void setFixed(Long millis) { this.fixedMillis = millis; }
    public void setZone(ZoneId zone) { this.zone = zone; }

    public long nowMillis() {
        return fixedMillis != null ? fixedMillis : System.currentTimeMillis();
    }

    public int minuteOfDay() {
        Instant i = Instant.ofEpochMilli(nowMillis());
        ZonedDateTime z = ZonedDateTime.ofInstant(i, zone);
        return z.getHour() * 60 + z.getMinute();
    }
}
