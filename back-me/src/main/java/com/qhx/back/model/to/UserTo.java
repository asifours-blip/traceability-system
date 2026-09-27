package com.qhx.back.model.to;

import lombok.Data;
@Data
public class UserTo {
    private String address;
    private String role; // UserRole 枚举名，只用于查询链上角色
}
