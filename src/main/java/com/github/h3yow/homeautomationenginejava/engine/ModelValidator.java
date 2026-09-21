package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 模型版本差异处理（核心语义）：
 * <ul>
 *   <li>未注册设备：DEVICE_NOT_FOUND；</li>
 *   <li>未支持命令：UNSUPPORTED_COMMAND，绝不按错误语义执行；</li>
 *   <li>必填参数缺失且模型未给默认值：MISSING_REQUIRED_FIELD；</li>
 *   <li>载荷类型非法：INVALID_PAYLOAD；</li>
 *   <li>模型中存在默认值但旧版本未上报的字段：确定性地用默认值补齐，不静默忽略；</li>
 *   <li>上报未知属性（不在模型已声明属性中）：UNKNOWN_CAPABILITY，明确拒绝整次上报，
 *       杜绝“静默丢弃”导致的解释不一致。</li>
 * </ul>
 */
@Component
public class ModelValidator {

    private final DeviceRegistry registry;

    public ModelValidator(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 取某命令的有效参数默认值（bare 视图）：
     * 命名空间键 "setPower.value" -> "value"；同时保留全局裸键（向后兼容）。
     * 同名时命名空间键优先，使不同命令可对同名参数（如 value）声明不同类型默认值。
     */
    private Map<String, Object> defaultsFor(String command, DeviceModel model) {
        Map<String, Object> effective = new LinkedHashMap<>();
        String prefix = command + ".";
        model.commandParamDefaults().forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                effective.put(k.substring(prefix.length()), v);
            } else if (!k.contains(".")) {
                effective.putIfAbsent(k, v);
            }
        });
        return effective;
    }

    /**
     * 校验并归一化命令参数：
     * 入参 -> 类型校验 -> 必填校验 -> 默认值补齐（仅补调用方未显式提供的键）。
     */
    public Validation validateCommand(String deviceId, String command, Map<String, Object> params) {
        DeviceModel model = registry.modelOf(deviceId);
        if (model == null) {
            return Validation.fail(RejectReason.DEVICE_NOT_FOUND,
                    "device not registered: " + deviceId);
        }
        Map<String, Object> input = params == null ? Map.of() : params;
        if (!model.supportsCommand(command)) {
            return Validation.fail(RejectReason.UNSUPPORTED_COMMAND,
                    "command '" + command + "' not in capabilities of model "
                            + model.modelId() + " v" + model.version());
        }
        Map<String, Object> defaults = defaultsFor(command, model);

        // 1) 对调用方显式给出的参数做类型检查：类型以该命令参数默认值类型为准；
        //    无默认值的参数要求非 null（数值/布尔/字符串的具体类型在执行映射时确定）。
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object value = e.getValue();
            if (value == null) {
                return Validation.fail(RejectReason.INVALID_PAYLOAD,
                        "param '" + e.getKey() + "' is null");
            }
            Object def = defaults.get(e.getKey());
            if (def != null && !compatibleType(def, value)) {
                return Validation.fail(RejectReason.INVALID_PAYLOAD,
                        "param '" + e.getKey() + "' expects "
                                + def.getClass().getSimpleName() + " but got "
                                + value.getClass().getSimpleName());
            }
        }

        // 2) 必填校验：命令声明 required 且既未提供也无默认值 -> 拒绝。
        for (String required : model.requiredOf(command)) {
            if (!input.containsKey(required) && !defaults.containsKey(required)) {
                return Validation.fail(RejectReason.MISSING_REQUIRED_FIELD,
                        "missing required param '" + required + "' for command '"
                                + command + "'");
            }
        }

        // 3) 归一化输出：命令参数全集 = 该命令默认值键 + required 声明键。
        //    先做未知参数拒绝，避免未知参数被静默忽略。
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (!defaults.containsKey(e.getKey())
                    && !model.requiredOf(command).contains(e.getKey())) {
                return Validation.fail(RejectReason.UNKNOWN_CAPABILITY,
                        "unknown param '" + e.getKey() + "' for command '" + command + "'");
            }
        }
        // 用模型默认值确定性补齐旧版本缺失字段，再以显式入参覆盖。
        Map<String, Object> normalized = new LinkedHashMap<>(defaults);
        normalized.putAll(input);
        return Validation.pass(normalized);
    }

    /**
     * 校验状态上报 patch：
     * 每个键必须是模型已声明属性，否则 UNKNOWN_CAPABILITY；
     * 类型必须与默认值类型兼容。缺失的已知属性不强制（上报是增量语义，
     * 设备初始状态已由模型默认值填充，即缺失字段的确定默认语义）。
     */
    public Validation validateReport(String deviceId, Map<String, Object> properties) {
        DeviceModel model = registry.modelOf(deviceId);
        if (model == null) {
            return Validation.fail(RejectReason.DEVICE_NOT_FOUND,
                    "device not registered: " + deviceId);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        if (properties != null) {
            for (Map.Entry<String, Object> e : properties.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (value == null) {
                    // 显式 null 不是“缺字段”，而是非法载荷。
                    return Validation.fail(RejectReason.INVALID_PAYLOAD,
                            "property '" + key + "' value is null");
                }
                if (!model.hasProperty(key)) {
                    return Validation.fail(RejectReason.UNKNOWN_CAPABILITY,
                            "unknown property '" + key + "' not declared by model "
                                    + model.modelId() + " v" + model.version());
                }
                Object def = model.propertyDefaults().get(key);
                if (def != null && !compatibleType(def, value)) {
                    return Validation.fail(RejectReason.INVALID_PAYLOAD,
                            "property '" + key + "' expects "
                                    + def.getClass().getSimpleName() + " but got "
                                    + value.getClass().getSimpleName());
                }
                patch.put(key, value);
            }
        }
        return Validation.pass(patch);
    }

    /**
     * 类型兼容规则（确定且宽松到 JSON 常见情况）：
     * 数值族内部兼容（Integer/Long/Double 等）；Boolean/String 必须严格同族。
     */
    private boolean compatibleType(Object expected, Object actual) {
        if (expected instanceof Number) {
            return actual instanceof Number;
        }
        if (expected instanceof Boolean) {
            return actual instanceof Boolean;
        }
        return expected.getClass().isInstance(actual);
    }

    /**
     * @param normalized 归一化后的安全载荷（命令为补齐默认值后的参数；上报为已校验 patch）
     */
    public record Validation(boolean ok, RejectReason reason, String detail,
                             Map<String, Object> normalized) {
        public static Validation pass(Map<String, Object> normalized) {
            return new Validation(true, null, "ok",
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(normalized)));
        }
        public static Validation fail(RejectReason reason, String detail) {
            return new Validation(false, reason, detail, Map.of());
        }
    }
}
