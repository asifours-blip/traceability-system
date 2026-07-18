package com.qhx.back.controller;

import com.qhx.back.model.Result;
import com.qhx.back.model.to.FileTo;
import com.qhx.back.service.IPFSService;
import io.ipfs.multibase.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;

@RestController
public class IPFSController
{
    @Autowired
    private IPFSService ipfsService;

    @PostMapping("/upload")
    public Result saveFile(@RequestParam("file") MultipartFile file)
    {
        String hash = ipfsService.saveFile(file);
        HashMap<String, String> resMap = new HashMap<>();
        resMap.put("hash", hash);
        return Result.success(resMap);
    }

    @PostMapping("/uploadBase64")
    public Result saveFileBase64(@RequestBody FileTo fileTo)
    {
        String hash = ipfsService.saveFileBase64(fileTo.getFile());
        HashMap<String, String> resMap = new HashMap<>();
        resMap.put("hash", hash);
        return Result.success(resMap);
    }

    @GetMapping("file/{hash}")
    public Result loadFile(@PathVariable("hash") String hash, HttpServletResponse response)
    {
        byte[] bytes = ipfsService.loadFile(hash);
        response.setHeader("Content-type", MediaType.ALL_VALUE);
        return Result.success(bytes);
    }

    @GetMapping("fileBase64/{hash}")
    public Result loadFileBase64(@PathVariable("hash") String hash)
    {
        byte[] bytes = ipfsService.loadFile(hash);
        String base64 = Base64.encodeBase64String(bytes);
        return Result.success(null,base64);
    }
}
