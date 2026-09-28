package com.qhx.back.controller;
import cn.hutool.json.JSONArray;
import com.qhx.back.annotation.RequireRole;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.enums.UserRole;
import com.qhx.back.interceptor.AddressInterceptor;
import com.qhx.back.model.Result;
import com.qhx.back.model.to.CreateUserTo;
import com.qhx.back.model.to.LoginTo;
import com.qhx.back.model.to.UserTo;
import com.qhx.back.service.AuthService;
import com.qhx.back.service.UserAccountService;
import com.qhx.back.util.ClientIpUtil;
import com.qhx.back.util.UserAddressUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
@RestController
@Api(tags = "用户接口")
public class UserController {
    @Autowired
    private AuthService authService;
    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private WeBaseClient weBaseClient;

    // 用户名 + 密码登录，返回 Bearer token；按账号 + IP 限流，超限 429
    @PostMapping("/login")
    @ApiOperation(value = "登录")
    public Result login(@RequestBody LoginTo loginTo, HttpServletRequest request) {
        return Result.success(authService.login(loginTo.getUsername(), loginTo.getPassword(), ClientIpUtil.resolve(request)));
    }

    // 撤销当前 token
    @PostMapping("/logout")
    @ApiOperation(value = "登出")
    public Result logout(HttpServletRequest request) {
        authService.logout(AddressInterceptor.resolveToken(request));
        return Result.success();
    }

    // 管理员查看账号列表
    @GetMapping("/admin/users")
    @ApiOperation(value = "账号列表")
    @RequireRole(UserRole.ADMIN)
    public Result listUsers() {
        return Result.success(userAccountService.listUsers());
    }

    // 管理员新建业务账号：链上已有角色则跳过授权交易；否则由管理员签名调用 addX，结果未知时返回 202 且账号保持停用
    @PostMapping("/admin/users")
    @ApiOperation(value = "新建账号")
    @RequireRole(UserRole.ADMIN)
    public Result createUser(@RequestBody CreateUserTo createUserTo) {
        return Result.success(userAccountService.createUser(createUserTo));
    }

    // 管理员停用账号：撤销全部 token，并由管理员签名调用 removeX 撤销链上角色
    @PostMapping("/admin/users/{id}/disable")
    @ApiOperation(value = "停用账号")
    @RequireRole(UserRole.ADMIN)
    public Result disableUser(@PathVariable Long id) {
        userAccountService.disableUser(id);
        return Result.success();
    }

    // 授权交易结果未知的账号：查证链上角色，已有则启用
    @PostMapping("/admin/users/{id}/verify-role")
    @ApiOperation(value = "查证账号链上角色")
    @RequireRole(UserRole.ADMIN)
    public Result verifyRole(@PathVariable Long id) {
        return Result.success(userAccountService.verifyRole(id));
    }

    // 授权交易结果未知且链上仍无角色：重新发送授权交易
    @PostMapping("/admin/users/{id}/grant-role")
    @ApiOperation(value = "重试授权链上角色")
    @RequireRole(UserRole.ADMIN)
    public Result retryGrant(@PathVariable Long id) {
        return Result.success(userAccountService.retryGrant(id));
    }

    // 查询某地址在链上是否拥有对应角色（用于核对账号表与链上状态）
    @GetMapping("/get/user/role")
    @ApiOperation(value = "获取用户角色")
    @RequireRole(UserRole.ADMIN)
    public Result getUserRole(UserTo userTo) {
        if (!UserAddressUtil.isLegalAddress(userTo.getAddress())) {
            return Result.error("地址不合法");
        }
        UserRole role = UserRole.parse(userTo.getRole());
        JSONArray result = weBaseClient.call(role.checkFunction(), Arrays.asList(userTo.getAddress()));
        return Result.success(result.getBool(0));
    }

}
