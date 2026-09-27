package com.qhx.back.trace;

import com.qhx.back.chain.TraceStage;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 三个阶段的业务字段清单：字段类型决定校验规则，isPublic 决定消费者扫码页是否展示。
 * 顺序与合约写入参数（去掉溯源号）一致。公开范围的取舍见 docs/business-flow.md「公开字段」。
 */
public final class TraceFields {

    public enum Type {
        /** 必填文本，不超过 64 字 */
        TEXT(64),
        /** 必填文本，不超过 200 字（地址类） */
        LONG_TEXT(200),
        /** 必填 IPFS CID：字母数字，不超过 128 */
        CID(128),
        /** 必填日期 yyyy-MM-dd，不晚于今天 */
        DATE(10),
        /** 必填正整数，不超过 10^12 */
        POSITIVE_INT(13);

        public final int maxLength;

        Type(int maxLength) {
            this.maxLength = maxLength;
        }
    }

    public static final class Field {
        public final String name;
        public final String label;
        public final Type type;
        public final boolean isPublic;

        Field(String name, String label, Type type, boolean isPublic) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.isPublic = isPublic;
        }
    }

    private static final Map<TraceStage, List<Field>> FIELDS = new EnumMap<>(TraceStage.class);

    static {
        FIELDS.put(TraceStage.PRODUCTION, Collections.unmodifiableList(Arrays.asList(
                new Field("companyName", "生产企业", Type.TEXT, true),
                new Field("productName", "产品名称", Type.TEXT, true),
                new Field("productionLocation", "产地", Type.LONG_TEXT, true),
                new Field("variety", "品种", Type.TEXT, true),
                new Field("productionBatch", "生产批次", Type.TEXT, true),
                // CID 本身不公开；图片经 GET /trace/{traceNumber}/file/production 按链上绑定读取
                new Field("productionCert", "生产认证", Type.CID, false),
                new Field("productTime", "生产日期", Type.DATE, true))));
        FIELDS.put(TraceStage.DISTRIBUTION, Collections.unmodifiableList(Arrays.asList(
                new Field("companyName", "分销企业", Type.TEXT, true),
                new Field("storageCondition", "存储条件", Type.TEXT, true),
                new Field("transportMethod", "运输方式", Type.TEXT, true),
                new Field("distributeBatch", "分销批次", Type.TEXT, false),
                new Field("storageLocation", "仓库地址", Type.LONG_TEXT, false),
                new Field("distributePrice", "分销价格", Type.POSITIVE_INT, false),
                new Field("distributeQuantity", "分销数量", Type.POSITIVE_INT, false),
                new Field("inspectionReport", "质检报告", Type.CID, false))));
        FIELDS.put(TraceStage.RETAIL, Collections.unmodifiableList(Arrays.asList(
                new Field("companyName", "零售企业", Type.TEXT, true),
                new Field("salePrice", "零售价格", Type.POSITIVE_INT, false),
                new Field("saleQuantity", "零售数量", Type.POSITIVE_INT, false),
                new Field("shelfLife", "保质期（天）", Type.POSITIVE_INT, true),
                new Field("invoiceNo", "单据号", Type.TEXT, false),
                new Field("saleTime", "销售日期", Type.DATE, true))));
    }

    private TraceFields() {
    }

    public static List<Field> of(TraceStage stage) {
        return FIELDS.get(stage);
    }

    public static Optional<Field> find(TraceStage stage, String name) {
        return FIELDS.get(stage).stream().filter(f -> f.name.equals(name)).findFirst();
    }

    /** 带图片的阶段：生产认证、质检报告；零售阶段没有文件 */
    public static Optional<String> fileField(TraceStage stage) {
        switch (stage) {
            case PRODUCTION:
                return Optional.of("productionCert");
            case DISTRIBUTION:
                return Optional.of("inspectionReport");
            default:
                return Optional.empty();
        }
    }
}
