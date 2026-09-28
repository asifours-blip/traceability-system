package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.enums.UserRole;
import com.qhx.back.exception.BusinessException;
import com.qhx.back.mapper.TraceAssignmentLogMapper;
import com.qhx.back.mapper.TraceBatchMapper;
import com.qhx.back.mapper.TraceReadModelMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.TraceAssignmentLog;
import com.qhx.back.model.TraceBatch;
import com.qhx.back.model.TraceReadModel;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.vo.PageQuery;
import com.qhx.back.model.vo.PageResult;
import com.qhx.back.service.FileService;
import com.qhx.back.service.ReadModelService;
import com.qhx.back.trace.ChainTraceReader;
import com.qhx.back.trace.TraceFields;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReadModelServiceImpl implements ReadModelService
{
    static final String CLAIMED = "CLAIMED";
    static final String PARTIAL = "PARTIAL";
    static final String UNCLAIMED = "UNCLAIMED";
    private static final String BACKFILL_REASON = "重建读模型：按链上写入者地址回填";
    private static final Map<TraceStage, String> LABELS = new EnumMap<>(TraceStage.class);

    static {
        LABELS.put(TraceStage.PRODUCTION, "生产");
        LABELS.put(TraceStage.DISTRIBUTION, "分销");
        LABELS.put(TraceStage.RETAIL, "零售");
    }

    @Autowired
    private TraceReadModelMapper readModelMapper;
    @Autowired
    private TraceBatchMapper batchMapper;
    @Autowired
    private TraceAssignmentLogMapper assignmentLogMapper;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private WeBaseClient weBaseClient;
    @Autowired
    private FileService fileService;
    @Value("${contract.address}")
    private String v2Address;
    @Value("${contract.v3.address:0x0}")
    private String v3Address;

    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /** 一个溯源号在链上的快照：写入者 + 已写入阶段的数据 */
    static final class Snapshot
    {
        final List<String> actors;
        List<String> designations;
        final Map<TraceStage, Map<String, Object>> data = new EnumMap<>(TraceStage.class);

        Snapshot(List<String> actors)
        {
            this.actors = actors;
        }
    }

    // ---------------------------------------------------------------- 单个溯源号

    @Override
    public TraceReadModel get(String traceNumber)
    {
        return traceNumber == null ? null : readModelMapper.selectById(traceNumber);
    }

    @Override
    public TraceReadModel refresh(String traceNumber)
    {
        TraceBatch bound = findBatch(traceNumber);
        String version = bound == null || bound.getContractVersion() == null ? "V2" : bound.getContractVersion();
        if (bound != null) requireBinding(bound);
        Snapshot snapshot = read(new ChainTraceReader(weBaseClient, version), traceNumber);
        if (snapshot == null && bound == null && !"0x0".equals(v3Address)) {
            snapshot = read(new ChainTraceReader(weBaseClient, "V3"), traceNumber);
        }
        if (snapshot == null) {
            return null;
        }
        if (bound != null && "V3".equals(version)) {
            syncV3Designations(bound, snapshot, null);
        }
        TraceReadModel existing = readModelMapper.selectById(traceNumber);
        TraceReadModel row = build(traceNumber, snapshot, existing == null ? null : existing.getListIndex());
        applyClaim(row, snapshot, findBatch(traceNumber));
        save(row, existing);
        return readModelMapper.selectById(traceNumber);
    }

    private Snapshot read(ChainTraceReader chain, String traceNumber)
    {
        List<String> actors = chain.actors(traceNumber);
        if (actors == null) {
            return null;
        }
        Snapshot s = new Snapshot(actors);
        s.designations = chain.designations(traceNumber);
        for (TraceStage stage : TraceStage.values()) {
            if (chain.actor(actors, stage) != null) {
                Map<String, Object> d = chain.stage(traceNumber, stage);
                if (d != null) {
                    s.data.put(stage, d);
                }
            }
        }
        return s;
    }

    private void requireBinding(TraceBatch batch)
    {
        String configured = "V3".equals(batch.getContractVersion()) ? v3Address : v2Address;
        if (batch.getContractAddress() == null || !batch.getContractAddress().equalsIgnoreCase(configured)) {
            throw new BusinessException(409, "批次绑定的合约地址与当前配置不一致，拒绝读取；请核对迁移和部署配置");
        }
    }

    private static String designation(Snapshot snapshot, int index)
    {
        if (snapshot.designations == null || snapshot.designations.size() != 2) return null;
        String address = snapshot.designations.get(index);
        return ChainTraceReader.ZERO_ADDRESS.equalsIgnoreCase(address) ? null : address;
    }

    /** v3 当前指定对象由链决定；账户无法映射时保留链上地址供查询，并明确告警。 */
    private int syncV3Designations(TraceBatch batch, Snapshot snapshot, Long operatorId)
    {
        int changed = 0;
        for (TraceStage stage : new TraceStage[]{TraceStage.DISTRIBUTION, TraceStage.RETAIL}) {
            String address = designation(snapshot, stage == TraceStage.DISTRIBUTION ? 0 : 1);
            if (address == null) continue;
            UserAccount target = accountByAddress(address,
                    stage == TraceStage.DISTRIBUTION ? UserRole.DISTRIBUTOR : UserRole.RETAILER);
            if (target == null) {
                log.warn("v3 批次 {} 的链上指定 {} 地址 {} 没有匹配的业务账号，保留链下归属并等待人工核对",
                        batch.getTraceNumber(), stage, address);
                continue;
            }
            Long current = stage == TraceStage.DISTRIBUTION ? batch.getDistributorId() : batch.getRetailerId();
            if (target.getId().equals(current)) continue;
            LambdaUpdateWrapper<TraceBatch> update = new LambdaUpdateWrapper<TraceBatch>()
                    .set(TraceBatch::getUpdatedAt, new Date()).eq(TraceBatch::getId, batch.getId());
            if (stage == TraceStage.DISTRIBUTION) {
                update.set(TraceBatch::getDistributorId, target.getId());
                if (current == null) update.isNull(TraceBatch::getDistributorId);
                else update.eq(TraceBatch::getDistributorId, current);
            } else {
                update.set(TraceBatch::getRetailerId, target.getId());
                if (current == null) update.isNull(TraceBatch::getRetailerId);
                else update.eq(TraceBatch::getRetailerId, current);
            }
            if (batchMapper.update(null, update) == 1) {
                TraceAssignmentLog entry = new TraceAssignmentLog();
                entry.setTraceNumber(batch.getTraceNumber());
                entry.setStage(stage.code());
                entry.setFromUserId(current);
                entry.setToUserId(target.getId());
                entry.setOperatorId(operatorId == null ? batch.getProducerId() : operatorId);
                entry.setReason("按 v3 链上当前指定对象同步");
                entry.setCreatedAt(new Date());
                assignmentLogMapper.insert(entry);
                if (stage == TraceStage.DISTRIBUTION) batch.setDistributorId(target.getId());
                else batch.setRetailerId(target.getId());
                log.warn("v3 批次 {} 链下 {} 指定 {} 与链上 {} 不一致，已按链上地址同步",
                        batch.getTraceNumber(), stage, current, address);
                changed++;
            }
        }
        return changed;
    }

    /** 由链上快照组装一行（不含认领状态） */
    private TraceReadModel build(String traceNumber, Snapshot s, Integer listIndex)
    {
        TraceReadModel row = new TraceReadModel();
        row.setTraceNumber(traceNumber);
        row.setListIndex(listIndex);
        int reached = 0;
        for (TraceStage stage : TraceStage.values()) {
            if (s.data.containsKey(stage)) {
                reached = stage.code();
            }
        }
        row.setStageReached(reached);
        row.setProducerAddress(lower(actor(s, TraceStage.PRODUCTION)));
        row.setDistributorAddress(lower(actor(s, TraceStage.DISTRIBUTION)));
        row.setRetailerAddress(lower(actor(s, TraceStage.RETAIL)));
        Map<String, Object> prod = s.data.get(TraceStage.PRODUCTION);
        if (prod != null) {
            row.setProductName(str(prod.get("productName")));
            row.setProducerCompany(str(prod.get("companyName")));
            row.setProductionLocation(str(prod.get("productionLocation")));
            row.setVariety(str(prod.get("variety")));
            row.setProductTime(str(prod.get("productTime")));
            row.setProductionTs((Long) prod.get("timestamp"));
            row.setProductionData(JSONUtil.toJsonStr(prod));
            row.setProductionCid(StrUtil.emptyToNull(str(prod.get("productionCert"))));
        }
        Map<String, Object> dist = s.data.get(TraceStage.DISTRIBUTION);
        if (dist != null) {
            row.setDistributionTs((Long) dist.get("timestamp"));
            row.setDistributionData(JSONUtil.toJsonStr(dist));
            row.setDistributionCid(StrUtil.emptyToNull(str(dist.get("inspectionReport"))));
        }
        Map<String, Object> retail = s.data.get(TraceStage.RETAIL);
        if (retail != null) {
            row.setRetailTs((Long) retail.get("timestamp"));
            row.setRetailData(JSONUtil.toJsonStr(retail));
        }
        return row;
    }

    /**
     * 认领状态：本系统有归属记录、且每个已写入阶段的链上写入者都是本系统指定的那个账号 → CLAIMED；
     * 有归属记录但某阶段对不上 → PARTIAL；没有归属记录 → UNCLAIMED。
     */
    private void applyClaim(TraceReadModel row, Snapshot s, TraceBatch batch)
    {
        if (batch == null) {
            String producer = actor(s, TraceStage.PRODUCTION);
            UserAccount acc = accountByAddress(producer);
            row.setClaimStatus(UNCLAIMED);
            row.setClaimNote(StrUtil.maxLength("本系统没有该批次的归属记录；生产阶段链上写入者 " + producer
                    + (acc == null ? " 没有对应账号" : " 对应账号 " + acc.getUsername() + " 的角色是 " + acc.getRole() + "，不是生产商")
                    + (TraceFieldsLength.fits(row.getTraceNumber()) ? "" : "；溯源号超过 64 字符，无法建立归属记录"), 490));
            return;
        }
        List<String> problems = new ArrayList<>();
        if ("V3".equals(batch.getContractVersion())) {
            for (TraceStage stage : new TraceStage[]{TraceStage.DISTRIBUTION, TraceStage.RETAIL}) {
                String designated = designation(s, stage == TraceStage.DISTRIBUTION ? 0 : 1);
                if (designated == null) continue;
                Long assigned = stage == TraceStage.DISTRIBUTION ? batch.getDistributorId() : batch.getRetailerId();
                UserAccount account = assigned == null ? null : userAccountMapper.selectById(assigned);
                if (account == null || !designated.equalsIgnoreCase(account.getChainAddress())) {
                    problems.add(LABELS.get(stage) + "阶段链上指定地址 " + designated + " 与链下归属不一致");
                }
            }
        }
        for (TraceStage stage : TraceStage.values()) {
            String writer = actor(s, stage);
            if (writer == null) {
                continue;
            }
            Long assigned = stage == TraceStage.PRODUCTION ? batch.getProducerId()
                    : stage == TraceStage.DISTRIBUTION ? batch.getDistributorId() : batch.getRetailerId();
            UserAccount acc = assigned == null ? null : userAccountMapper.selectById(assigned);
            if (acc == null) {
                problems.add(LABELS.get(stage) + "阶段链上写入者 " + writer + " 在本系统没有对应的指定账号");
            } else if (!writer.equalsIgnoreCase(acc.getChainAddress())) {
                problems.add(LABELS.get(stage) + "阶段链上写入者 " + writer + " 与本系统指定的 " + acc.getUsername() + " 不一致");
            }
        }
        row.setClaimStatus(problems.isEmpty() ? CLAIMED : PARTIAL);
        row.setClaimNote(problems.isEmpty() ? null : StrUtil.maxLength(String.join("；", problems), 490));
    }

    /** 与现有行比较，只有内容变化时才写（synced_at 表示最近一次内容变化），返回 CREATED / UPDATED / UNCHANGED */
    private String save(TraceReadModel row, TraceReadModel existing)
    {
        if (existing != null && sameContent(row, existing)) {
            return "UNCHANGED";
        }
        row.setSyncedAt(new Date());
        if (existing == null) {
            try {
                readModelMapper.insert(row);
                return "CREATED";
            } catch (DuplicateKeyException e) {
                // 并发刷新：另一请求已插入，改为更新
            }
        }
        readModelMapper.updateById(row);
        return "UPDATED";
    }

    private static boolean sameContent(TraceReadModel a, TraceReadModel b)
    {
        return Objects.equals(a.getListIndex(), b.getListIndex())
                && Objects.equals(a.getStageReached(), b.getStageReached())
                && Objects.equals(a.getProductName(), b.getProductName())
                && Objects.equals(a.getProducerCompany(), b.getProducerCompany())
                && Objects.equals(a.getProductionLocation(), b.getProductionLocation())
                && Objects.equals(a.getVariety(), b.getVariety())
                && Objects.equals(a.getProductTime(), b.getProductTime())
                && Objects.equals(a.getProductionTs(), b.getProductionTs())
                && Objects.equals(a.getDistributionTs(), b.getDistributionTs())
                && Objects.equals(a.getRetailTs(), b.getRetailTs())
                && Objects.equals(a.getProducerAddress(), b.getProducerAddress())
                && Objects.equals(a.getDistributorAddress(), b.getDistributorAddress())
                && Objects.equals(a.getRetailerAddress(), b.getRetailerAddress())
                && Objects.equals(a.getProductionData(), b.getProductionData())
                && Objects.equals(a.getDistributionData(), b.getDistributionData())
                && Objects.equals(a.getRetailData(), b.getRetailData())
                && Objects.equals(a.getProductionCid(), b.getProductionCid())
                && Objects.equals(a.getDistributionCid(), b.getDistributionCid())
                && Objects.equals(a.getClaimStatus(), b.getClaimStatus())
                && Objects.equals(a.getClaimNote(), b.getClaimNote());
    }

    // ---------------------------------------------------------------- 重建

    @Override
    public Map<String, Object> rebuild(Long operatorId)
    {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new BusinessException(409, "读模型正在重建，请稍后再试");
        }
        try {
            return doRebuild(operatorId);
        } finally {
            rebuilding.set(false);
        }
    }

    private Map<String, Object> doRebuild(Long operatorId)
    {
        long started = System.currentTimeMillis();
        Map<String, Integer> rows = new LinkedHashMap<>();
        rows.put("created", 0);
        rows.put("updated", 0);
        rows.put("unchanged", 0);
        Map<String, Integer> claims = new LinkedHashMap<>();
        claims.put(CLAIMED, 0);
        claims.put(PARTIAL, 0);
        claims.put(UNCLAIMED, 0);
        Map<String, Integer> files = new LinkedHashMap<>();
        int batchesBackfilled = 0;
        int partnersBackfilled = 0;
        List<Map<String, String>> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String version : new String[]{"V2", "V3"}) {
            if ("V3".equals(version) && "0x0".equals(v3Address)) continue;
            ChainTraceReader chain = new ChainTraceReader(weBaseClient, version);
            List<String> list = chain.list();
            for (int i = 0; i < list.size(); i++) {
            String tn = list.get(i);
            TraceBatch bound = findBatch(tn);
            if (bound != null) requireBinding(bound);
            if (bound != null && bound.getContractVersion() != null
                    && !version.equals(bound.getContractVersion())) continue;
            if (!seen.add(tn)) continue;
            try {
                Snapshot s = read(chain, tn);
                if (s == null) {
                    // 列表里有、但按号查不到：记错误，不写入
                    errors.add(error(tn, "getAgroFoodList 中存在，但 getStageActors 返回不存在"));
                    continue;
                }
                // 1. 归属回填（只补缺，不改本系统已有的指定关系）
                TraceBatch batch = findBatch(tn);
                if (batch == null) {
                    batch = backfillBatch(tn, s, operatorId, version);
                    if (batch != null) {
                        batchesBackfilled++;
                    }
                } else {
                    partnersBackfilled += "V3".equals(version)
                            ? syncV3Designations(batch, s, operatorId) : backfillPartners(batch, s, operatorId);
                    batch = findBatch(tn);
                }
                // 2. 读模型行
                TraceReadModel existing = readModelMapper.selectById(tn);
                TraceReadModel row = build(tn, s, i);
                applyClaim(row, s, batch);
                String result = save(row, existing);
                rows.merge(result.toLowerCase(), 1, Integer::sum);
                claims.merge(row.getClaimStatus(), 1, Integer::sum);
                // 3. 文件绑定
                for (TraceStage stage : new TraceStage[]{TraceStage.PRODUCTION, TraceStage.DISTRIBUTION}) {
                    Map<String, Object> d = s.data.get(stage);
                    String field = TraceFields.fileField(stage).orElse(null);
                    String cid = d == null || field == null ? null : StrUtil.emptyToNull(str(d.get(field)));
                    if (cid == null) {
                        continue;
                    }
                    FileService.BindOutcome outcome = fileService.reconcile(tn, stage, cid, accountByAddress(actor(s, stage)));
                    files.merge(outcome.name(), 1, Integer::sum);
                }
            } catch (BusinessException e) {
                errors.add(error(tn, e.getMessage()));
            }
            }
        }

        // 4. 链上已不存在的行（例如换了合约）：删除
        List<String> stale = readModelMapper.selectList(new LambdaQueryWrapper<TraceReadModel>().select(TraceReadModel::getTraceNumber))
                .stream().map(TraceReadModel::getTraceNumber).filter(tn -> !seen.contains(tn)).collect(Collectors.toList());
        if (!stale.isEmpty()) {
            readModelMapper.deleteBatchIds(stale);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("onChain", seen.size());
        report.put("rows", rows);
        report.put("removedStale", stale.size());
        report.put("claims", claims);
        report.put("batchesBackfilled", batchesBackfilled);
        report.put("partnersBackfilled", partnersBackfilled);
        report.put("files", files);
        report.put("errors", errors);
        report.put("elapsedMs", System.currentTimeMillis() - started);
        log.info("读模型重建完成：{}", report);
        return report;
    }

    /** 旧批次：按链上生产阶段写入者匹配生产商账号，匹配上才建归属记录；下游阶段已写入的，同样按写入者匹配 */
    private TraceBatch backfillBatch(String tn, Snapshot s, Long operatorId, String version)
    {
        UserAccount producer = accountByAddress(actor(s, TraceStage.PRODUCTION), UserRole.PRODUCER);
        if (producer == null || !TraceFieldsLength.fits(tn)) {
            return null;
        }
        UserAccount distributor = accountByAddress("V3".equals(version) ? designation(s, 0) : actor(s, TraceStage.DISTRIBUTION), UserRole.DISTRIBUTOR);
        UserAccount retailer = accountByAddress("V3".equals(version) ? designation(s, 1) : actor(s, TraceStage.RETAIL), UserRole.RETAILER);
        Map<String, Object> prod = s.data.get(TraceStage.PRODUCTION);
        TraceBatch batch = new TraceBatch();
        batch.setTraceNumber(tn);
        batch.setProductName(prod == null ? null : StrUtil.maxLength(str(prod.get("productName")), 120));
        batch.setProducerId(producer.getId());
        batch.setContractVersion(version);
        batch.setContractAddress("V3".equals(version) ? v3Address : v2Address);
        batch.setDistributorId(distributor == null ? null : distributor.getId());
        batch.setRetailerId(retailer == null ? null : retailer.getId());
        batch.setCreatedAt(new Date());
        batch.setUpdatedAt(new Date());
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException e) {
            return findBatch(tn);
        }
        if (distributor != null) {
            logAssignment(tn, TraceStage.DISTRIBUTION, distributor.getId(), operatorId == null ? producer.getId() : operatorId);
        }
        if (retailer != null) {
            logAssignment(tn, TraceStage.RETAIL, retailer.getId(), operatorId == null ? producer.getId() : operatorId);
        }
        return batch;
    }

    /** 已有归属记录但下游对象为空、而链上该阶段已写入且写入者能匹配到账号：补上（条件更新，只填空值） */
    private int backfillPartners(TraceBatch batch, Snapshot s, Long operatorId)
    {
        int filled = 0;
        Long operator = operatorId == null ? batch.getProducerId() : operatorId;
        if (batch.getDistributorId() == null) {
            UserAccount d = accountByAddress(actor(s, TraceStage.DISTRIBUTION), UserRole.DISTRIBUTOR);
            if (d != null && batchMapper.update(null, new LambdaUpdateWrapper<TraceBatch>()
                    .set(TraceBatch::getDistributorId, d.getId()).set(TraceBatch::getUpdatedAt, new Date())
                    .eq(TraceBatch::getId, batch.getId()).isNull(TraceBatch::getDistributorId)) == 1) {
                logAssignment(batch.getTraceNumber(), TraceStage.DISTRIBUTION, d.getId(), operator);
                filled++;
            }
        }
        if (batch.getRetailerId() == null) {
            UserAccount r = accountByAddress(actor(s, TraceStage.RETAIL), UserRole.RETAILER);
            if (r != null && batchMapper.update(null, new LambdaUpdateWrapper<TraceBatch>()
                    .set(TraceBatch::getRetailerId, r.getId()).set(TraceBatch::getUpdatedAt, new Date())
                    .eq(TraceBatch::getId, batch.getId()).isNull(TraceBatch::getRetailerId)) == 1) {
                logAssignment(batch.getTraceNumber(), TraceStage.RETAIL, r.getId(), operator);
                filled++;
            }
        }
        return filled;
    }

    private void logAssignment(String tn, TraceStage stage, Long to, Long operator)
    {
        TraceAssignmentLog log = new TraceAssignmentLog();
        log.setTraceNumber(tn);
        log.setStage(stage.code());
        log.setFromUserId(null);
        log.setToUserId(to);
        log.setOperatorId(operator);
        log.setReason(BACKFILL_REASON);
        log.setCreatedAt(new Date());
        assignmentLogMapper.insert(log);
    }

    // ---------------------------------------------------------------- 分页查询

    @Override
    public PageResult<Map<String, Object>> searchPublic(String keyword, PageQuery page)
    {
        String like = likePattern(keyword);
        long total = readModelMapper.countPublic(like);
        List<Map<String, Object>> records = total <= page.offset() ? new ArrayList<>()
                : readModelMapper.pagePublic(like, page.offset(), page.size).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("traceNumber", r.getTraceNumber());
                    m.put("productName", r.getProductName());
                    m.put("companyName", r.getProducerCompany());
                    m.put("productionLocation", r.getProductionLocation());
                    m.put("variety", r.getVariety());
                    m.put("productTime", r.getProductTime());
                    m.put("stageReached", r.getStageReached());
                    m.put("productionTimestamp", r.getProductionTs());
                    return m;
                }).collect(Collectors.toList());
        return new PageResult<>(records, total, page.page, page.size);
    }

    @Override
    public PageResult<Map<String, Object>> unclaimed(PageQuery page)
    {
        long total = readModelMapper.countUnclaimed();
        List<Map<String, Object>> records = total <= page.offset() ? new ArrayList<>()
                : readModelMapper.pageUnclaimed(page.offset(), page.size).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("traceNumber", r.getTraceNumber());
                    m.put("productName", r.getProductName());
                    m.put("companyName", r.getProducerCompany());
                    m.put("stageReached", r.getStageReached());
                    m.put("claimStatus", r.getClaimStatus());
                    m.put("claimNote", r.getClaimNote());
                    m.put("producerAddress", r.getProducerAddress());
                    m.put("distributorAddress", r.getDistributorAddress());
                    m.put("retailerAddress", r.getRetailerAddress());
                    m.put("syncedAt", r.getSyncedAt());
                    return m;
                }).collect(Collectors.toList());
        return new PageResult<>(records, total, page.page, page.size);
    }

    /** 关键字转成 LIKE 模式：转义 \ % _，两侧加 %；空白返回 null（不过滤） */
    static String likePattern(String keyword)
    {
        if (StrUtil.isBlank(keyword)) {
            return null;
        }
        String k = StrUtil.maxLength(keyword.trim(), 64);
        if (k.endsWith("...")) {
            k = k.substring(0, k.length() - 3);
        }
        return "%" + k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    // ---------------------------------------------------------------- 工具

    private TraceBatch findBatch(String tn)
    {
        return TraceFieldsLength.fits(tn)
                ? batchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>().eq(TraceBatch::getTraceNumber, tn)) : null;
    }

    private static String actor(Snapshot s, TraceStage stage)
    {
        String a = s.actors.get(stage.actorIndex());
        return a == null || ChainTraceReader.ZERO_ADDRESS.equalsIgnoreCase(a) ? null : a;
    }

    private UserAccount accountByAddress(String address)
    {
        if (StrUtil.isBlank(address)) {
            return null;
        }
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .apply("LOWER(chain_address) = {0}", address.trim().toLowerCase()))
                .stream().findFirst().orElse(null);
    }

    /** 地址匹配且角色一致才算 */
    private UserAccount accountByAddress(String address, UserRole role)
    {
        UserAccount acc = accountByAddress(address);
        return acc != null && role.name().equals(acc.getRole()) ? acc : null;
    }

    private static Map<String, String> error(String tn, String message)
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("traceNumber", tn);
        m.put("message", message);
        return m;
    }

    private static String str(Object o)
    {
        return o == null ? null : String.valueOf(o);
    }

    private static String lower(String s)
    {
        return s == null ? null : s.toLowerCase();
    }

    /** trace_batch.trace_number 是 VARCHAR(64)：更长的旧溯源号只能留在读模型里 */
    static final class TraceFieldsLength
    {
        static boolean fits(String tn)
        {
            return tn != null && tn.length() <= 64;
        }
    }
}
