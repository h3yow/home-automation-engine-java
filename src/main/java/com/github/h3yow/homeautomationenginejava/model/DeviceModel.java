package com.github.h3yow.homeautomationenginejava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 设备模型描述：不同固件版本上报的能力可能不同。
 * 对缺失字段给出确定性默认值；对未知能力/未支持命令由校验方明确拒绝。
 *
 * <p>模型一旦发布即为不可变快照（所有集合做不可变拷贝），保证多线程读取一致。
 */
public final class DeviceModel {

    private final String modelId;
    private final int version;
    /** 能力名 -> 该能力下支持的命令集合 */
    private final Map<String, Set<String>> capabilityCommands;
    /** 状态属性名 -> 默认值（旧版本缺字段时使用） */
    private final Map<String, Object> propertyDefaults;
    /** 命令参数名 -> 默认值（旧版本缺字段时使用） */
    private final Map<String, Object> commandParamDefaults;
    /** 每个命令声明其必填参数（默认值表之外仍必须提供的参数） */
    private final Map<String, Set<String>> requiredParams;

    public DeviceModel(String modelId, int version,
                       Map<String, Set<String>> capabilityCommands,
                       Map<String, Object> propertyDefaults,
                       Map<String, Object> commandParamDefaults,
                       Map<String, Set<String>> requiredParams) {
        this.modelId = modelId;
        this.version = version;
        this.capabilityCommands = immutableMapOfSets(capabilityCommands);
        this.propertyDefaults = immutableCopy(propertyDefaults);
        this.commandParamDefaults = immutableCopy(commandParamDefaults);
        this.requiredParams = immutableMapOfSets(requiredParams);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> src) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(src == null ? Map.of() : src));
    }

    private static Map<String, Set<String>> immutableMapOfSets(Map<String, Set<String>> src) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        if (src != null) {
            src.forEach((k, v) -> copy.put(k,
                    Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        }
        return Collections.unmodifiableMap(copy);
    }

    public String modelId() { return modelId; }
    public int version() { return version; }
    public Map<String, Set<String>> capabilityCommands() { return capabilityCommands; }
    public Map<String, Object> propertyDefaults() { return propertyDefaults; }
    public Map<String, Object> commandParamDefaults() { return commandParamDefaults; }
    public Map<String, Set<String>> requiredParams() { return requiredParams; }

    /**
     * 命令是否被任一已声明能力支持。
     */
    public boolean supportsCommand(String command) {
        return capabilityOf(command) != null;
    }

    /**
     * 返回命令所属能力名；未支持返回 null（校验方据此给 UNSUPPORTED_COMMAND）。
     */
    public String capabilityOf(String command) {
        if (command == null) {
            return null;
        }
        // 命令在能力表中按声明顺序查找；正常建模下命令唯一归属，确定性无歧义。
        for (Map.Entry<String, Set<String>> e : capabilityCommands.entrySet()) {
            if (e.getValue().contains(command)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 属性是否是模型已声明属性（出现在能力属性默认表中即视为已知）。
     */
    public boolean hasProperty(String property) {
        return property != null && propertyDefaults.containsKey(property);
    }

    /**
     * 命令的必填参数集合（永不为 null）。
     */
    public Set<String> requiredOf(String command) {
        return requiredParams.getOrDefault(command, Set.of());
    }
}
