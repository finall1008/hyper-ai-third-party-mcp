package io.github.finall1008.xiaoaimcp.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class McpConfigValidator {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern HEADER = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");

    private McpConfigValidator() {
    }

    public static void validateAll(List<McpServer> servers) throws ValidationException {
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (McpServer server : servers) {
            validate(server);
            if (!ids.add(server.id())) {
                throw new ValidationException("服务器 ID 重复");
            }
            if (!names.add(server.name())) {
                throw new ValidationException("服务器名称重复：" + server.name());
            }
        }
    }

    public static void validate(McpServer server) throws ValidationException {
        String name = server.name();
        if (!NAME.matcher(name).matches() || name.contains("__")) {
            throw new ValidationException("名称需为 1–64 位字母、数字、点、下划线或连字符，且不能包含 __");
        }

        if (!server.transport().equals("http") && !server.transport().equals("sse")) {
            throw new ValidationException("传输类型只支持 http 或 sse");
        }

        try {
            URI uri = new URI(server.url());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                throw new ValidationException("URL 必须是有效的 http:// 或 https:// 地址");
            }
        } catch (URISyntaxException e) {
            throw new ValidationException("URL 格式无效");
        }

        Set<String> headerNames = new HashSet<>();
        for (Map.Entry<String, String> entry : server.headers().entrySet()) {
            String header = entry.getKey().trim();
            if (!HEADER.matcher(header).matches()) {
                throw new ValidationException("请求头名称无效：" + header);
            }
            if (!headerNames.add(header.toLowerCase(Locale.ROOT))) {
                throw new ValidationException("请求头名称重复：" + header);
            }
            if (entry.getValue().contains("\r") || entry.getValue().contains("\n")) {
                throw new ValidationException("请求头值不能包含换行符：" + header);
            }
        }
    }

    public static final class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }
}
