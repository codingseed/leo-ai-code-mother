package com.leo.leoaicodemother.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.leo.leoaicodemother.exception.ErrorCode;
import com.leo.leoaicodemother.exception.ThrowUtils;
import com.leo.leoaicodemother.manager.CosManager;
import com.leo.leoaicodemother.service.ScreenshotService;
import com.leo.leoaicodemother.utils.WebScreenshotUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 截图服务实现类
 * 实现了截图生成、上传到对象存储以及本地文件清理等功能
 */
@Service
@Slf4j
public class ScreenshotServiceImpl implements ScreenshotService {

    @Resource
    private CosManager cosManager; // 对象存储管理器

    /**
     * 生成网页截图并上传到对象存储
     *
     * @param webUrl 要截图的网页URL
     * @return 截图在对象存储中的访问URL
     */
    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        // 参数校验：检查URL是否为空
        ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "截图的网址不能为空");
        log.info("开始生成网页截图，URL：{}", webUrl);
        // 本地截图：生成并保存网页截图到本地
        String localScreenshotPath = WebScreenshotUtils.saveWebPageScreenshot(webUrl);
        ThrowUtils.throwIf(StrUtil.isBlank(localScreenshotPath), ErrorCode.OPERATION_ERROR, "生成网页截图失败");
        // 上传图片到 COS：将本地截图上传到对象存储
        try {
            String cosUrl = uploadScreenshotToCos(localScreenshotPath);
            ThrowUtils.throwIf(StrUtil.isBlank(cosUrl), ErrorCode.OPERATION_ERROR, "上传截图到对象存储失败");
            log.info("截图上传成功，URL：{}", cosUrl);
            return cosUrl;
        } finally {
            // 清理本地文件：无论上传成功与否，都删除本地临时文件
            cleanupLocalFile(localScreenshotPath);
        }
    }

    /**
     * 上传截图到对象存储
     *
     * @param localScreenshotPath 本地截图路径
     * @return 对象存储访问URL，失败返回null
     */
    private String uploadScreenshotToCos(String localScreenshotPath) {
        // 参数校验：检查路径是否为空
        if (StrUtil.isBlank(localScreenshotPath)) {
            return null;
        }
        // 检查文件是否存在
        File screenshotFile = new File(localScreenshotPath);
        if (!screenshotFile.exists()) {
            log.error("截图文件不存在: {}", localScreenshotPath);
            return null;
        }
        // 生成 COS 对象键：使用UUID和日期生成唯一文件名
        String fileName = UUID.randomUUID().toString().substring(0, 8) + "_compressed.jpg";
        String cosKey = generateScreenshotKey(fileName);
        return cosManager.uploadFile(cosKey, screenshotFile);
    }

    /**
     * 生成截图的对象存储键
     * 格式：/screenshots/2025/07/31/filename.jpg
     *
     * @param fileName 文件名
     * @return 完整的对象存储键
     */
    private String generateScreenshotKey(String fileName) {
        // 生成日期路径：按年/月/日格式组织文件
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return String.format("/screenshots/%s/%s", datePath, fileName);
    }

    /**
     * 清理本地文件
     *
     * @param localFilePath 本地文件路径
     */
    private void cleanupLocalFile(String localFilePath) {
        File localFile = new File(localFilePath);
        if (localFile.exists()) {
            // 删除文件并记录日志
            FileUtil.del(localFile);
            log.info("清理本地文件成功: {}", localFilePath);
        }
    }
}
