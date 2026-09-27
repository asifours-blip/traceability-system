package com.qhx.back.chain;

import cn.hutool.core.util.StrUtil;

/**
 * 把交易的失败/未知结果映射成返回给前端的 HTTP 状态码与中文说明。
 * revert 文本与合约 v2 一致（contracts/README.md「revert 信息」），并已在本地真实链上逐条复现。
 */
public final class ChainErrors {

    private ChainErrors() {
    }

    public static final class Mapped {
        public final int status;
        public final String message;

        Mapped(int status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    public static Mapped of(TxOutcome outcome) {
        switch (outcome.getKind()) {
            case REVERTED:
                return revert(outcome.getReason());
            case REJECTED:
                return rejected(outcome);
            case NOT_SENT:
                return new Mapped(503, "WeBASE-Front 不可达，交易没有发出，可以直接重试（" + outcome.getReason() + "）");
            case UNKNOWN:
                return new Mapped(202, "交易已提交但结果未确认，请调用查证接口确认，查证前不要重复提交（" + outcome.getReason() + "）");
            default:
                throw new IllegalArgumentException("CONFIRMED 不是错误");
        }
    }

    static Mapped revert(String reason) {
        String r = StrUtil.nullToEmpty(reason);
        String suffix = "（链上 revert：" + r + "）";
        if (r.equals("Trace: traceNumber already exists")) {
            return new Mapped(409, "溯源号已存在，生产信息不能重复录入" + suffix);
        }
        if (r.equals("Trace: distribution already recorded")) {
            return new Mapped(409, "分销信息已录入，不能重复录入" + suffix);
        }
        if (r.equals("Trace: retail already recorded")) {
            return new Mapped(409, "零售信息已录入，不能重复录入" + suffix);
        }
        if (r.equals("Trace: distribution not recorded yet")) {
            return new Mapped(409, "尚未录入分销信息，不能录入零售信息" + suffix);
        }
        if (r.equals("Trace: traceNumber does not exist")) {
            return new Mapped(404, "溯源号不存在，需先录入生产信息" + suffix);
        }
        if (r.equals("Trace: traceNumber is empty")) {
            return new Mapped(400, "溯源号不能为空" + suffix);
        }
        if (r.endsWith("caller does not have the Producer role") || r.endsWith("caller does not have the Distributor role")
                || r.endsWith("caller does not have the Retailer role")) {
            return new Mapped(403, "当前账户没有对应的链上角色" + suffix);
        }
        if (r.equals("Ownable: caller is not the owner")) {
            return new Mapped(403, "只有合约管理员可以执行该操作" + suffix);
        }
        if (r.equals("Roles: account already has role") || r.equals("Roles: account does not have role")) {
            return new Mapped(409, "链上角色状态与操作冲突" + suffix);
        }
        if (r.equals("Roles: account is the zero address")) {
            return new Mapped(400, "地址不能为 0 地址" + suffix);
        }
        return new Mapped(422, "链上执行失败" + suffix);
    }

    private static Mapped rejected(TxOutcome outcome) {
        Integer code = outcome.getFrontCode();
        if (code != null && code == 201015) {
            return new Mapped(502, "签名账户的私钥不在 WeBASE-Front 中，交易没有发出（" + outcome.getReason() + "）");
        }
        if (code != null && code == 201151) {
            return new Mapped(400, "参数与合约 ABI 不匹配，交易没有发出（" + outcome.getReason() + "）");
        }
        return new Mapped(502, "WeBASE-Front 拒绝了请求，交易没有发出（" + outcome.getReason() + "）");
    }
}
