package com.ifoodbuy.spring_ai_demo.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件系统查看工具
 * 提供只读的文件系统操作，包括：
 * - 列出目录内容
 * - 读取文件内容
 * - 获取文件/目录信息
 * <p>
 * 注意：此工具只提供只读操作，不包含创建、删除、修改功能
 */
@Component
public class FileSystemViewTool {

    /**
     * 执行文件系统查看操作
     * 
     * @param request 请求参数
     * @return 操作结果
     */
    @Tool(description = "查看文件系统的工具，提供只读操作。可以列出目录内容、读取文件内容、获取文件信息。不包含创建、删除、修改操作。")
    public String executeFileSystemView(FileSystemViewRequest request) {
        try {
            Path path = Paths.get(request.path).normalize();
            
            // 安全检查：确保路径存在
            if (!Files.exists(path)) {
                return "错误：路径不存在 - " + request.path;
            }

            return switch (request.operation) {
                case "list" -> listDirectory(path);
                case "read" -> readFile(path);
                case "info" -> getFileInfo(path);
                default -> "错误：不支持的操作类型 '" + request.operation + "'。支持的操作：list, read, info";
            };
        } catch (SecurityException e) {
            return "错误：没有权限访问路径 - " + request.path + "。原因：" + e.getMessage();
        } catch (Exception e) {
            return "错误：操作失败 - " + e.getMessage();
        }
    }

    /**
     * 列出目录内容
     */
    private String listDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return "错误：路径不是目录 - " + path;
        }
        
        List<String> items = new ArrayList<>();
        items.add("目录内容：" + path.toString());
        items.add("---");
        
        try (Stream<Path> paths = Files.list(path)) {
            List<Path> sortedPaths = paths.sorted().toList();
            
            for (Path item : sortedPaths) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(item, BasicFileAttributes.class);
                    String type = attrs.isDirectory() ? "[目录]" : "[文件]";
                    String size = attrs.isDirectory() ? "" : " (" + formatSize(attrs.size()) + ")";
                    String modified = formatTime(attrs.lastModifiedTime());
                    
                    items.add(String.format("%s %s%s - 修改时间: %s", 
                            type, 
                            item.getFileName().toString(),
                            size,
                            modified));
                } catch (IOException e) {
                    items.add("[无法访问] " + item.getFileName().toString());
                }
            }
        }
        
        return String.join("\n", items);
    }

    /**
     * 读取文件内容
     */
    private String readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "错误：文件不存在 - " + path;
        }
        
        if (Files.isDirectory(path)) {
            return "错误：路径是目录，不是文件 - " + path;
        }
        
        // 安全检查：限制文件大小（例如 1MB）
        long fileSize = Files.size(path);
        if (fileSize > 1024 * 1024) {
            return "错误：文件太大（超过 1MB），无法读取。文件大小：" + formatSize(fileSize);
        }
        
        // 读取文件内容
        String content = Files.readString(path);
        
        return String.format("文件内容：%s\n---\n%s", path.toString(), content);
    }

    /**
     * 获取文件/目录信息
     */
    private String getFileInfo(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        
        List<String> info = new ArrayList<>();
        info.add("文件/目录信息：" + path.toString());
        info.add("---");
        info.add("类型：" + (attrs.isDirectory() ? "目录" : "文件"));
        info.add("大小：" + formatSize(attrs.size()));
        info.add("创建时间：" + formatTime(attrs.creationTime()));
        info.add("修改时间：" + formatTime(attrs.lastModifiedTime()));
        info.add("访问时间：" + formatTime(attrs.lastAccessTime()));
        
        if (!attrs.isDirectory()) {
            String mimeType = Files.probeContentType(path);
            if (mimeType != null) {
                info.add("MIME 类型：" + mimeType);
            }
        }
        
        return String.join("\n", info);
    }

    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 格式化时间
     */
    private String formatTime(FileTime fileTime) {
        Instant instant = fileTime.toInstant();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        return formatter.format(instant);
    }

    /**
     * 文件系统查看请求参数
     */
    public static class FileSystemViewRequest {
        @JsonProperty(required = true)
        @JsonPropertyDescription("操作类型：'list' 列出目录内容，'read' 读取文件内容，'info' 获取文件/目录信息")
        public String operation;

        @JsonProperty(required = true)
        @JsonPropertyDescription("文件或目录的路径（绝对路径或相对路径）")
        public String path;
    }
}
