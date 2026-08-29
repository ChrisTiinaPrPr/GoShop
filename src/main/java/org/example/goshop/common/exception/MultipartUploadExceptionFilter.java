package org.example.goshop.common.exception;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 捕获在 DispatcherServlet 之前发生的 multipart 文件超限异常。
 *
 * <p>Tomcat 在某个 Filter 调用 {@code request.getParameter()} 时会立即解析 multipart，
 * 此时抛出的异常无法进入 {@link GlobalExceptionHandler}。该过滤器必须排在 Spring Security
 * 之前，才能将这类异常转换为统一业务响应。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartUploadExceptionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (RuntimeException e) {
            if (isMultipartRequest(request) && UploadSizeExceptionSupport.isExceeded(e)) {
                writeFileTooLargeResponse(response);
                return;
            }
            throw e;
        }
    }

    private boolean isMultipartRequest(HttpServletRequest request) {
        return StringUtils.startsWithIgnoreCase(
                request.getContentType(),
                MediaType.MULTIPART_FORM_DATA_VALUE
        );
    }

    private void writeFileTooLargeResponse(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 此处内容固定且没有用户输入，直接写 JSON 可避免异常处理路径依赖 Jackson 版本。
        response.getWriter().write("{\"code\":40001,\"message\":\"上传文件不能超过 5MB\",\"data\":null}");
    }
}
