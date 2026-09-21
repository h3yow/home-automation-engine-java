package com.github.h3yow.homeautomationenginejava.model;

/**
 * 命令结果：五态 + 可诊断上下文。
 * 终态（EFFECTIVE/REJECTED/TIMEOUT/FAILED）一旦确认不再变更，
 * 重复的生效确认按 commandId 幂等忽略，不产生重复副作用。
 */
public record CommandResult(
        String commandId,
        String deviceId,
        CommandOutcome outcome,
        RejectReason rejectReason,
        String detail,
        long version,
        long atMillis) {

    public static CommandResult accepted(String commandId, String deviceId, long now) {
        return new CommandResult(commandId, deviceId, CommandOutcome.ACCEPTED,
                null, "accepted", -1L, now);
    }
}
