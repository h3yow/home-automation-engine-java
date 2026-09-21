package com.github.h3yow.homeautomationenginejava.engine;

import java.util.Set;

/**
 * 调用身份：所属家庭集合（成员身份），用于多住户隔离。
 */
public record Principal(String principalId, Set<String> householdIds, String displayName) {
}
