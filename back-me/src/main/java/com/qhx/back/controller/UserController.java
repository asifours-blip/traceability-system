package com.qhx.back.controller;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import com.qhx.back.context.AddressContext;
import com.qhx.back.enums.RoleType;
import com.qhx.back.model.Result;
import com.qhx.back.model.to.LoginTo;
import com.qhx.back.model.to.RegisterTo;
import com.qhx.back.model.to.UserTo;
import com.qhx.back.util.HttpUtil;
import com.qhx.back.util.UserAddressUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Arrays;
@RestController
@Api(tags = "用户接口")
public class UserController {
    @Autowired
    private HttpUtil httpUtil;
    @Value("${contract.owner}")
    private String owner;
    @PostMapping("/login")
    @ApiOperation(value = "登录")
    public Result login(@RequestBody LoginTo loginTo) {
        if (!UserAddressUtil.isLegalAddress(loginTo.getAddress())) {
            return Result.error("登录地址不合法");
        }
        String type = loginTo.getType();
        String address = loginTo.getAddress();
        String funcName = getUserRoleFunctionName(type);
        JSONArray result = httpUtil.call("", funcName, Arrays.asList(address));
        if (result.getBool(0)) {
            return Result.success();
        }
        return Result.error("登录失败，用户未注册");
    }

    @PostMapping("/register")
    @ApiOperation(value = "注册")
    public Result register(@RequestBody @NonNull RegisterTo registerTo) {
        if (!UserAddressUtil.isLegalAddress(registerTo.getAddress())) {
            return Result.error("注册地址不合法");
        }
        if (!UserAddressUtil.isLegalAddress(registerTo.getRoleAddress())) {
            return Result.error("role Address地址不合法");
        }
        String type = registerTo.getType();
        String funcName = getAddUserFunctionName(type);
        httpUtil.sendTransaction(registerTo.getRoleAddress(), funcName, Arrays.asList(registerTo.getAddress()));
        return Result.success();
    }

    // 管理员添加用户
    @PostMapping("/add/user")
    @ApiOperation(value = "添加用户")
    public Result addUser(@RequestBody UserTo userTo) {
        if(!StrUtil.equals(owner, AddressContext.getAddress())){
                        return Result.error("必须管理员权限");
        }
        String type = userTo.getType();
        String funcName = getAddUserFunctionName(type);
        httpUtil.sendTransaction(AddressContext.getAddress(), funcName, Arrays.asList(userTo.getAddress()));
        return Result.success();
    }

    // 管理员删除用户
    @PostMapping("/delete/user")
    @ApiOperation(value = "删除用户")
    public Result deleteUser(@RequestBody UserTo userTo) {
        if(!StrUtil.equals(owner, AddressContext.getAddress())){
            return Result.error("必须管理员权限");
        }
        if(StrUtil.equals(userTo.getAddress(), owner)){
            return Result.error("不能撤销管理员");
        }
        String type = userTo.getType();
        String funcName = getUserRoleFunctionName(type).replace("is", "renounce");
        httpUtil.sendTransaction(userTo.getAddress(), funcName, Arrays.asList());
        return Result.success();
    }

    // 获取用户是否有对应角色
    @GetMapping("/get/user/role")
    @ApiOperation(value = "获取用户角色")
    public Result getUserRole(UserTo userTo) {
        String type = userTo.getType();
        String funcName = getUserRoleFunctionName(type);
        JSONArray result = httpUtil.call("", funcName, Arrays.asList(userTo.getAddress()));
        return Result.success(result.getBool(0));
    }

    // 私有方法：根据用户类型获取角色检查函数名
    private String getUserRoleFunctionName(String type) {
        if (RoleType.PRODUCER.getCode().equals(type)) {
            return "isProducer";
        } else if (RoleType.DISTRIBUTOR.getCode().equals(type)) {
            return "isDistributor";
        } else if (RoleType.RETAILER.getCode().equals(type)) {
            return "isRetailer";
        }
        throw new IllegalArgumentException("无效的用户类型");
    }

    // 私有方法：根据用户类型获取添加用户函数名
    private String getAddUserFunctionName(String type) {
        if (RoleType.PRODUCER.getCode().equals(type)) {
            return "addProducer";
        } else if (RoleType.DISTRIBUTOR.getCode().equals(type)) {
            return "addDistributor";
        } else if (RoleType.RETAILER.getCode().equals(type)) {
            return "addRetailer";
        }
        throw new IllegalArgumentException("无效的用户类型");
    }

}
