package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.hutool.json.JSONArray;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.enums.UserRole;
import com.qhx.back.exception.BusinessException;
import com.qhx.back.exception.ChainTxException;
import com.qhx.back.exception.WeBaseFrontException;
import com.qhx.back.mapper.AccountRoleGrantMapper;
import com.qhx.back.mapper.ChainTxMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.AccountRoleGrant;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.to.CreateUserTo;
import com.qhx.back.model.vo.UserVO;
import com.qhx.back.service.AuthService;
import com.qhx.back.service.ChainTxService;
import com.qhx.back.service.UserAccountService;
import com.qhx.back.util.UserAddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserAccountServiceImpl implements UserAccountService
{
    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,64}$");

    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private ChainTxService chainTxService;
    @Autowired
    private AccountRoleGrantMapper accountRoleGrantMapper;
    @Autowired
    private ChainTxMapper chainTxMapper;
    @Autowired
    private WeBaseClient weBaseClient;
    @Value("${contract.v3.address:0x0}")
    private String v3Address;

    @Override
    public UserVO createUser(CreateUserTo to)
    {
        UserRole role = UserRole.parse(to.getRole());
        if (!role.isBusinessRole()) {
            throw new IllegalArgumentException("管理员账号只能通过启动引导创建");
        }
        String username = StrUtil.trim(to.getUsername());
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名只能包含字母、数字、下划线，长度 3-64");
        }
        if (to.getPassword() == null || to.getPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
        String chainAddress = StrUtil.trim(to.getChainAddress());
        if (!UserAddressUtil.isLegalAddress(chainAddress)) {
            throw new IllegalArgumentException("链上地址不合法");
        }
        if (userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username)) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getChainAddress, chainAddress)) > 0) {
            throw new IllegalArgumentException("该链上地址已绑定其他账号");
        }

        // 先确保 v3 角色确认上链；单边失败时不启用账号，重试按链上状态幂等补齐。
        ensureV3Role(role, chainAddress);
        // 幂等：先读链上角色。已有该角色就不再发授权交易（避免 addX 因「已有角色」revert），直接建号
        if (hasChainRole(role, chainAddress)) {
            UserAccount account = insertAccount(to, username, role, chainAddress, true);
            AccountRoleGrant grant = saveGrant(account.getId(), role, AccountRoleGrant.ALREADY_ON_CHAIN, null,
                    "链上已拥有该角色，跳过授权交易");
            return UserVO.of(account, grant);
        }

        // 由当前管理员签名授角色（onlyOwner）；回执确认成功才启用账号
        ChainTx tx;
        try {
            tx = chainTxService.submit(role.addFunction(), Collections.singletonList(chainAddress));
        } catch (ChainTxException e) {
            if (e.getStatus() != 202) {
                // 明确失败或未发出：不建号，管理员修正后可直接重试
                throw e;
            }
            // 结果未知：建号但保持停用，状态 PENDING；查证确认链上已有角色后才启用
            UserAccount account = insertAccount(to, username, role, chainAddress, false);
            ChainTx pending = e.getData() instanceof ChainTx ? (ChainTx) e.getData() : null;
            AccountRoleGrant grant = saveGrant(account.getId(), role, AccountRoleGrant.PENDING,
                    pending == null ? null : pending.getId(), "授权交易结果未知：" + StrUtil.maxLength(e.getMessage(), 400));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user", UserVO.of(account, grant));
            data.put("tx", pending);
            throw new ChainTxException(202, "账号已创建但暂不可登录：授权交易结果未知，请在账号列表点「查证」确认链上角色后启用", data);
        }
        UserAccount account = insertAccount(to, username, role, chainAddress, true);
        AccountRoleGrant grant = saveGrant(account.getId(), role, AccountRoleGrant.GRANTED_BY_TX, tx.getId(), "授权交易已确认");
        return UserVO.of(account, grant);
    }

    @Override
    public UserVO verifyRole(Long userId)
    {
        UserAccount user = requireBusinessUser(userId);
        UserRole role = UserRole.parse(user.getRole());
        AccountRoleGrant grant = accountRoleGrantMapper.selectById(userId);
        if (grant == null || !AccountRoleGrant.PENDING.equals(grant.getState())) {
            return UserVO.of(user, grant);
        }
        // 有交易记录先走交易查证（有哈希能补上回执）；结论以链上 isX 为准
        if (grant.getTxId() != null) {
            try {
                chainTxService.verify(grant.getTxId());
            } catch (ChainTxException e) {
                log.warn("查证授权交易 #{} 未完成：{}", grant.getTxId(), e.getMessage());
            }
        }
        if (!hasChainRole(role, user.getChainAddress())) {
            ChainTx tx = grant.getTxId() == null ? null : chainTxMapper.selectById(grant.getTxId());
            String txState = tx == null ? "无记录" : tx.getState();
            throw new BusinessException(409, "链上仍没有该角色（授权交易状态：" + txState + "），账号保持停用；"
                    + "可稍后再查证，或点「重试授权」重新发送授权交易", UserVO.of(user, grant));
        }
        ensureV3Role(role, user.getChainAddress());
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .set(UserAccount::getEnabled, true)
                .set(UserAccount::getUpdatedAt, new Date())
                .eq(UserAccount::getId, userId));
        grant = saveGrant(userId, role, AccountRoleGrant.GRANTED_BY_TX, grant.getTxId(), "查证确认链上已有角色，账号已启用");
        return UserVO.of(userAccountMapper.selectById(userId), grant);
    }

    @Override
    public UserVO retryGrant(Long userId)
    {
        UserAccount user = requireBusinessUser(userId);
        UserRole role = UserRole.parse(user.getRole());
        AccountRoleGrant grant = accountRoleGrantMapper.selectById(userId);
        if (grant == null || !AccountRoleGrant.PENDING.equals(grant.getState())) {
            throw new BusinessException(409, "只有授权状态为「待确认」的账号可以重试授权");
        }
        if (hasChainRole(role, user.getChainAddress())) {
            // 之前那笔其实已经上链：直接走查证启用，不再发交易
            return verifyRole(userId);
        }
        ChainTx tx;
        try {
            tx = chainTxService.submit(role.addFunction(), Collections.singletonList(user.getChainAddress()));
        } catch (ChainTxException e) {
            ChainTx pending = e.getData() instanceof ChainTx ? (ChainTx) e.getData() : null;
            if (e.getStatus() == 202 && pending != null) {
                saveGrant(userId, role, AccountRoleGrant.PENDING, pending.getId(), "重试授权结果仍未知：" + StrUtil.maxLength(e.getMessage(), 400));
            }
            throw e;
        }
        ensureV3Role(role, user.getChainAddress());
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .set(UserAccount::getEnabled, true)
                .set(UserAccount::getUpdatedAt, new Date())
                .eq(UserAccount::getId, userId));
        grant = saveGrant(userId, role, AccountRoleGrant.GRANTED_BY_TX, tx.getId(), "重试授权交易已确认，账号已启用");
        return UserVO.of(userAccountMapper.selectById(userId), grant);
    }

    private boolean hasChainRole(UserRole role, String address)
    {
        return hasChainRole("V2", role, address);
    }

    private boolean hasChainRole(String version, UserRole role, String address)
    {
        JSONArray result;
        try {
            result = weBaseClient.call(version, role.checkFunction(), Collections.singletonList(address));
        } catch (WeBaseFrontException e) {
            throw new BusinessException(503, "读取链上角色失败，未做任何改动：" + e.mes);
        }
        String value = result == null || result.size() != 1 ? null : result.getStr(0);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new BusinessException(502, "无法解析链上 " + role.checkFunction() + " 的返回：" + result);
        }
        return "true".equalsIgnoreCase(value);
    }

    /** 已有账号和新账号都用同一幂等路径补齐 v3 角色；UNKNOWN 不算成功。 */
    private ChainTx ensureV3Role(UserRole role, String address)
    {
        if (v3Address == null || "0x0".equals(v3Address)) return null;
        if (hasChainRole("V3", role, address)) return null;
        return chainTxService.submitToContract("V3", role.addFunction(), Collections.singletonList(address));
    }

    @Override
    public Map<String, Object> migrateV3Roles()
    {
        if (v3Address == null || "0x0".equals(v3Address)) {
            throw new BusinessException(409, "未配置 v3 合约地址，不能迁移角色");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        int already = 0, granted = 0;
        Map<String, String> failed = new LinkedHashMap<>();
        for (UserAccount user : userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEnabled, true).orderByAsc(UserAccount::getId))) {
            UserRole role = UserRole.parse(user.getRole());
            if (!role.isBusinessRole()) continue;
            try {
                ChainTx tx = ensureV3Role(role, user.getChainAddress());
                if (tx == null) already++; else granted++;
            } catch (RuntimeException e) {
                failed.put(user.getUsername(), e.getMessage());
                log.error("v3 角色迁移未确认：{}", user.getUsername(), e);
            }
        }
        report.put("alreadyOnChain", already);
        report.put("confirmedGranted", granted);
        report.put("failedOrUnknown", failed);
        return report;
    }

    private UserAccount insertAccount(CreateUserTo to, String username, UserRole role, String chainAddress, boolean enabled)
    {
        Date now = new Date();
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(authService.hashPassword(to.getPassword()));
        account.setRole(role.name());
        account.setChainAddress(chainAddress);
        account.setCompanyName(StrUtil.trim(to.getCompanyName()));
        account.setEnabled(enabled);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        userAccountMapper.insert(account);
        return account;
    }

    private AccountRoleGrant saveGrant(Long userId, UserRole role, String state, Long txId, String note)
    {
        AccountRoleGrant existing = accountRoleGrantMapper.selectById(userId);
        AccountRoleGrant grant = existing == null ? new AccountRoleGrant() : existing;
        grant.setUserId(userId);
        grant.setRole(role.name());
        grant.setState(state);
        grant.setTxId(txId);
        grant.setNote(StrUtil.maxLength(note, 490));
        grant.setUpdatedAt(new Date());
        if (existing == null) {
            grant.setCreatedAt(new Date());
            accountRoleGrantMapper.insert(grant);
        } else {
            accountRoleGrantMapper.updateById(grant);
        }
        return grant;
    }

    private UserAccount requireBusinessUser(Long userId)
    {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!UserRole.parse(user.getRole()).isBusinessRole()) {
            throw new BusinessException(400, "管理员没有链上业务角色");
        }
        return user;
    }

    @Override
    public List<UserVO> listUsers()
    {
        Map<Long, AccountRoleGrant> grants = accountRoleGrantMapper.selectList(null).stream()
                .collect(Collectors.toMap(AccountRoleGrant::getUserId, g -> g));
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>().orderByAsc(UserAccount::getId))
                .stream().map(u -> UserVO.of(u, grants.get(u.getId()))).collect(Collectors.toList());
    }

    @Override
    public void disableUser(Long userId)
    {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        UserRole role = UserRole.parse(user.getRole());
        if (!role.isBusinessRole()) {
            throw new IllegalArgumentException("不能停用管理员");
        }

        // 先停用账号并撤销全部 token，保证本地身份立即失效；再由管理员签名撤销链上角色
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .set(UserAccount::getEnabled, false)
                .set(UserAccount::getUpdatedAt, new Date())
                .eq(UserAccount::getId, userId));
        authService.revokeAllSessions(userId);

        try {
            if (hasChainRole(role, user.getChainAddress())) {
                chainTxService.submit(role.removeFunction(), Collections.singletonList(user.getChainAddress()));
            }
            if (v3Address != null && !"0x0".equals(v3Address)
                    && hasChainRole("V3", role, user.getChainAddress())) {
                chainTxService.submitToContract("V3", role.removeFunction(), Collections.singletonList(user.getChainAddress()));
            }
        } catch (ChainTxException e) {
            log.error("用户 {} 已停用，但链上 {} 未确认成功", user.getUsername(), role.removeFunction(), e);
            throw new ChainTxException(e.getStatus(), "账号已停用并已撤销登录，但链上撤销角色未确认成功：" + e.getMessage(), e.getData());
        }
    }
}
