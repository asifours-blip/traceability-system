package com.qhx.back.model.vo;

import com.qhx.back.model.AccountRoleGrant;
import com.qhx.back.model.UserAccount;
import lombok.Data;

import java.util.Date;

/**
 * 返回给前端的账号信息，不含密码哈希。
 */
@Data
public class UserVO
{
    private Long id;
    private String username;
    private String role;
    private String chainAddress;
    private String companyName;
    private Boolean enabled;
    private Date createdAt;
    // 链上角色授权状态：GRANTED_BY_TX / ALREADY_ON_CHAIN / PENDING；管理员与历史账号为空
    private String roleState;
    // 授权交易记录 id（PENDING 时用于查证）
    private Long roleTxId;
    private String roleNote;

    public static UserVO of(UserAccount account, AccountRoleGrant grant)
    {
        UserVO vo = of(account);
        if (grant != null) {
            vo.setRoleState(grant.getState());
            vo.setRoleTxId(grant.getTxId());
            vo.setRoleNote(grant.getNote());
        }
        return vo;
    }

    public static UserVO of(UserAccount account)
    {
        UserVO vo = new UserVO();
        vo.setId(account.getId());
        vo.setUsername(account.getUsername());
        vo.setRole(account.getRole());
        vo.setChainAddress(account.getChainAddress());
        vo.setCompanyName(account.getCompanyName());
        vo.setEnabled(account.getEnabled());
        vo.setCreatedAt(account.getCreatedAt());
        return vo;
    }
}
