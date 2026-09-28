package com.qhx.back.controller;

import com.qhx.back.annotation.RequireRole;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.Result;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;
import com.qhx.back.file.FileNames;
import com.qhx.back.model.vo.PageQuery;
import com.qhx.back.service.BatchService;
import com.qhx.back.service.FileService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@Slf4j
@Api(tags = "溯源接口")
public class TraceController {
    @Autowired
    private BatchService batchService;

    // 消费者扫码详情（免登录）：只返回公开字段，见 docs/business-flow.md「公开字段」
    @GetMapping("/trace/detail/{traceNumber}")
    @ApiOperation(value = "溯源信息（公开字段）")
    public Result getTrace(@PathVariable String traceNumber) {
        return Result.success(batchService.publicDetail(traceNumber));
    }

    // 消费者分页查询（免登录）：只查读模型，只含公开字段
    @GetMapping("/trace/search")
    @ApiOperation(value = "溯源查询（分页，公开字段）")
    public Result search(@RequestParam(required = false) String keyword,
                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Result.success(batchService.searchPublic(keyword, PageQuery.of(page, size)));
    }

    /**
     * 消费者读取某阶段已绑定的文件（免登录）：CID 从链上该溯源号该阶段的字段读出，调用方不能指定任意 CID；
     * 只有状态为 BOUND 且与链上 CID 一致的文件可读。边读边写，不把文件读进内存。
     * 图片内联展示，其他类型（PDF）一律按附件下载；错误时返回 JSON（404 FILE_NOT_BOUND / 410 FILE_MISSING / 503 IPFS_UNAVAILABLE）。
     */
    @GetMapping("/trace/{traceNumber}/file/{stage}")
    @ApiOperation(value = "读取已绑定到溯源号的公开文件（production / distribution）")
    public void getPublicFile(@PathVariable String traceNumber, @PathVariable String stage, HttpServletResponse response) throws IOException {
        FileService.PublicFile file = batchService.publicFile(traceNumber, stage);
        try (InputStream in = file.stream) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(file.mimeType);
            response.setContentLengthLong(file.size);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            response.setHeader("Cache-Control", "public, max-age=300");
            response.setHeader("Content-Disposition", contentDisposition(file.image ? "inline" : "attachment", file.fileName));
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            long copied = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                copied += n;
            }
            if (copied != file.size) {
                // 响应头已发出，只能记录；客户端会因长度不符而判定下载不完整
                log.error("公开文件 {} {} 长度不符：登记 {} 字节，实际读出 {} 字节", traceNumber, stage, file.size, copied);
            }
        }
    }

    /** RFC 6266 / 5987：ASCII 兜底名 + UTF-8 编码的完整文件名 */
    static String contentDisposition(String type, String fileName) {
        String encoded;
        try {
            encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return type + "; filename=\"" + FileNames.asciiFallback(fileName) + "\"; filename*=UTF-8''" + encoded;
    }

    // 生产商录入生产信息并指定下游分销商；回执确认成功才返回 200，data 为交易记录（含哈希与块高）
    @PostMapping("/producer/add")
    @RequireRole(UserRole.PRODUCER)
    @ApiOperation(value = "生产商录入生产信息")
    public Result addProducer(@RequestBody ProducerTo producerTO) {
        return Result.success(batchService.submitProduction(producerTO));
    }

    // 被指定的分销商录入分销信息并指定下游零售商
    @PostMapping("/distributor/add")
    @RequireRole(UserRole.DISTRIBUTOR)
    @ApiOperation(value = "分销商录入分销信息")
    public Result addDistributor(@RequestBody DistributorTo distributorTO) {
        return Result.success(batchService.submitDistribution(distributorTO));
    }

    // 被指定的零售商录入零售信息
    @PostMapping("/retailer/add")
    @RequireRole(UserRole.RETAILER)
    @ApiOperation(value = "零售商录入零售信息")
    public Result addRetailer(@RequestBody RetailerTo retailerTO) {
        return Result.success(batchService.submitRetail(retailerTO));
    }
}
