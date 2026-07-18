package com.qhx.back.service.impl;

import com.qhx.back.service.IPFSService;
import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.ipfs.multihash.Multihash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Service
public class IPFSServiceImpl implements IPFSService
{

    @Autowired
    private IPFS ipfs;

    @Override
    public String saveFile(MultipartFile file)
    {
        try
        {
            InputStream stream = new ByteArrayInputStream(file.getBytes());
            NamedStreamable.InputStreamWrapper inputStreamWrapper = new NamedStreamable.InputStreamWrapper(stream);
            MerkleNode merkleNode = ipfs.add(inputStreamWrapper).get(0);
            return merkleNode.hash.toBase58();
        } catch (Exception e)
        {
            throw new RuntimeException("连接节点失败", e);
        }
    }

    @Override
    public byte[] loadFile(String hash)
    {
        try
        {
            Multihash filePointer = Multihash.fromBase58(hash);
            return ipfs.cat(filePointer);
        } catch (Exception e)
        {
            throw new RuntimeException("连接节点失败", e);
        }
    }

    @Override
    public String saveFileBase64(String file)
    {
        try
        {
            // 解码Base64字符串为字节数组
            byte[] decodedBytes = java.util.Base64.getDecoder().decode(file);
            InputStream stream = new ByteArrayInputStream(decodedBytes);
            NamedStreamable.InputStreamWrapper inputStreamWrapper = new NamedStreamable.InputStreamWrapper(stream);
            MerkleNode merkleNode = ipfs.add(inputStreamWrapper).get(0);
            return merkleNode.hash.toBase58();
        } catch (Exception e)
        {
            throw new RuntimeException("连接节点失败", e);
        }
    }

}
