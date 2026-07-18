package com.qhx.back.service;

import org.springframework.web.multipart.MultipartFile;

public interface IPFSService
{
    public String saveFile(MultipartFile file);

    public byte[] loadFile(String hash);

    public String saveFileBase64(String file);
}
