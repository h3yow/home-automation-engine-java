package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.engine.ModelRegistry;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelRegistry 版本裁决单测：精确命中、向旧版本回退、完全不支持时明确拒绝。
 */
class ModelRegistryTest {

    private DeviceModel model(String id, int version) {
        return new DeviceModel(id, version, Set.of("c"), Map.of(), Map.of(),
                Map.of(), Set.of("a"), Set.of(), Map.of());
    }

    @Test
    void versionResolutionIsDeterministic() {
        ModelRegistry registry = new ModelRegistry();
        registry.registerModel(model("m", 1));
        registry.registerModel(model("m", 3));

        assertEquals(3, registry.resolveModel("m", 3).version(), "精确版本命中");
        assertEquals(3, registry.resolveModel("m", 4).version(),
                "更高未登记版本回退到最新的已登记旧版本（前向兼容）");
        assertEquals(1, registry.resolveModel("m", 2).version(),
                "中间未登记版本回退到不高于它的最新版本 v1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.resolveModel("m", 0));
        assertTrue(ex.getMessage().contains("UNSUPPORTED_MODEL_VERSION"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.resolveModel("unknown-model", 1),
                "完全未知模型必须明确拒绝");
    }
}
