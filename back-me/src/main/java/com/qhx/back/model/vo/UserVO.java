package com.qhx.back.model.vo;

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
