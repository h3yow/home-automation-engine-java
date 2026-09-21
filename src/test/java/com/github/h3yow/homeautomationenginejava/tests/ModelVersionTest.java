package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.engine.ModelValidator;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备模型版本差异：缺失字段默认语义、未知能力/命令/参数明确拒绝、类型非法拒绝。
 */
class ModelVersionTest {

    private static final Logger log = LoggerFactory.getLogger(ModelVersionTest.class);

    private TestKit kit;

    @BeforeEach
    void setUp() {
        kit = new TestKit();
        kit.seedTwoHomes();
        // 旧版本灯 v1：只有开关
        kit.registry.registerDevice("lamp-old", "home-a", TestKit.lampV1(), true);
        // 新版本灯 v2：增加亮度
        kit.registry.registerDevice("lamp-new", "home-a", TestKit.lampV2(), true);
    }

    @Test
    @DisplayName("旧模型缺字段：注册即用模型默认值填充，brightness 对 v1 未知")
    void missingFieldsGetDefaults() {
        Object oldPower = kit.stateService.snapshot("lamp-old").property("power");
        assertEquals(false, oldPower);
        // v1 模型没有 brightness 属性：上报它必须被拒绝，而不是静默忽略。
        var r = kit.modelValidator.validateReport("lamp-old", Map.of("brightness", 80));
        assertFalse(r.ok());
        assertEquals(RejectReason.UNKNOWN_CAPABILITY, r.reason());
        log.info("[TEST] old model unknown property rejected: {}", r.detail());

        // v2 模型 brightness 有默认 50
        assertEquals(50, kit.stateService.snapshot("lamp-new").property("brightness"));
    }

    @Test
    @DisplayName("未支持命令：旧设备执行新命令被明确拒绝")
    void unsupportedCommandRejected() {
        var r = kit.modelValidator.validateCommand("lamp-old", "setBrightness",
                Map.of("value", 80));
        assertFalse(r.ok());
        assertEquals(RejectReason.UNSUPPORTED_COMMAND, r.reason());
        log.info("[TEST] unsupported command: {}", r.detail());

        var ok = kit.modelValidator.validateCommand("lamp-new", "setBrightness",
                Map.of("value", 80));
        assertTrue(ok.ok());
        assertEquals(80, ok.normalized().get("value"));
    }

    @Test
    @DisplayName("命令参数命名空间默认值：setPower.value=Boolean 与 setBrightness.value=Integer 各自正确")
    void namespacedCommandDefaults() {
        var boolDefault = kit.modelValidator.validateCommand("lamp-new", "setPower", Map.of());
        assertTrue(boolDefault.ok());
        assertEquals(Boolean.TRUE, boolDefault.normalized().get("value"));

        var intDefault = kit.modelValidator.validateCommand("lamp-new", "setBrightness", Map.of());
        assertTrue(intDefault.ok());
        assertEquals(50, intDefault.normalized().get("value"));

        // 类型错配必须拒绝（布尔给数值参数）
        var bad = kit.modelValidator.validateCommand("lamp-new", "setBrightness",
                Map.of("value", true));
        assertFalse(bad.ok());
        assertEquals(RejectReason.INVALID_PAYLOAD, bad.reason());
        log.info("[TEST] type mismatch rejected: {}", bad.detail());
    }

    @Test
    @DisplayName("未知参数与必填缺失：明确拒绝而非静默忽略/错误执行")
    void unknownParamAndMissingRequired() {
        DeviceModel strict = new DeviceModel("strict-x", 1,
                Map.of("cap", Set.of("doWork")),
                Map.of("state", "idle"),
                Map.of(),
                Map.of("doWork", Set.of("mode")));
        kit.registry.registerDevice("dev-strict", "home-a", strict, true);

        var missing = kit.modelValidator.validateCommand("dev-strict", "doWork", Map.of());
        assertFalse(missing.ok());
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, missing.reason());

        var unknown = kit.modelValidator.validateCommand("dev-strict", "doWork",
                Map.of("mode", "fast", "hack", 1));
        assertFalse(unknown.ok());
        assertEquals(RejectReason.UNKNOWN_CAPABILITY, unknown.reason());
        log.info("[TEST] missing={} unknown={}", missing.reason(), unknown.reason());
    }
}
