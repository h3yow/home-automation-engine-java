package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import org.springframework.stereotype.Service;

/**
 * 设备模型登记处：登记各版本能力模型，并对设备上报的模型版本做兼容性裁决。
 * 默认语义：未登记版本 -> 若同 modelId 的最接近版本声明 forwardCompatible 则采用，
 * 否则明确拒绝（UNSUPPORTED_MODEL_VERSION），不按猜测语义执行。
 */
@Service
public class ModelRegistry {

    public void registerModel(DeviceModel model) {
        // TODO 骨架
    }

    public DeviceModel resolveModel(String modelId, int version) {
        // TODO 骨架
        return null;
    }

    public DeviceModel modelFor(Device device) {
        // TODO 骨架
        return null;
    }
}
