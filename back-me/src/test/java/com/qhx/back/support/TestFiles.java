package com.qhx.back.support;

import com.qhx.back.mapper.FileObjectMapper;
import com.qhx.back.model.FileObject;

import java.util.Date;

/**
 * 直接在 file_object 里放一条 UPLOADED 记录：给不关心文件本身的测试（交易状态机、鉴权）用，
 * 让阶段交易引用的 CID 通过「必须是本账号上传的文件」这条校验。文件上传本身的测试走 /upload。
 */
public final class TestFiles {

    private TestFiles() {
    }

    public static FileObject seedUploaded(FileObjectMapper mapper, Long uploaderId, String cid) {
        FileObject row = new FileObject();
        row.setCid(cid);
        row.setSha256("0".repeat(64));
        row.setSizeBytes(1L);
        row.setMimeType("image/png");
        row.setFileName("seed.png");
        row.setUploaderId(uploaderId);
        row.setStatus("UPLOADED");
        row.setUnpinned(false);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        mapper.insert(row);
        return row;
    }
}
